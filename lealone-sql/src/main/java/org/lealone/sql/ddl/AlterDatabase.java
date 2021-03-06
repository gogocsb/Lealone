/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.sql.ddl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lealone.common.concurrent.ConcurrentUtils;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.CaseInsensitiveMap;
import org.lealone.common.util.StatementBuilder;
import org.lealone.common.util.StringUtils;
import org.lealone.db.Database;
import org.lealone.db.DbObjectType;
import org.lealone.db.LealoneDatabase;
import org.lealone.db.RunMode;
import org.lealone.db.session.ServerSession;
import org.lealone.net.NetNode;
import org.lealone.net.NetNodeManagerHolder;
import org.lealone.sql.SQLStatement;
import org.lealone.storage.Storage;

/**
 * This class represents the statement
 * ALTER DATABASE
 */
public class AlterDatabase extends DatabaseStatement {

    private final Database db;
    private String[] newHostIds;
    private String[] oldHostIds;

    public AlterDatabase(ServerSession session, Database db, RunMode runMode, CaseInsensitiveMap<String> parameters) {
        super(session, db.getName());
        this.db = db;
        this.runMode = runMode;
        if (parameters != null && !parameters.isEmpty()) {
            this.parameters = new CaseInsensitiveMap<>(parameters);
            validateParameters();
        }
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_DATABASE;
    }

    @Override
    public int update() {
        checkRight();
        synchronized (LealoneDatabase.getInstance().getLock(DbObjectType.DATABASE)) {
            RunMode oldRunMode = db.getRunMode();
            if (runMode == null) {
                if (oldRunMode == RunMode.CLIENT_SERVER)
                    clientServer2ClientServer();
                else if (oldRunMode == RunMode.REPLICATION)
                    replication2Replication();
                else if (oldRunMode == RunMode.SHARDING)
                    sharding2Sharding();
            } else if (oldRunMode == RunMode.CLIENT_SERVER) {
                if (runMode == RunMode.CLIENT_SERVER)
                    clientServer2ClientServer();
                else if (runMode == RunMode.REPLICATION)
                    scaleOutClientServer2Replication();
                else if (runMode == RunMode.SHARDING)
                    scaleOutClientServer2Sharding();
            } else if (oldRunMode == RunMode.REPLICATION) {
                if (runMode == RunMode.CLIENT_SERVER)
                    scaleInReplication2ClientServer();
                else if (runMode == RunMode.REPLICATION)
                    replication2Replication();
                else if (runMode == RunMode.SHARDING)
                    scaleOutReplication2Sharding();
            } else if (oldRunMode == RunMode.SHARDING) {
                if (runMode == RunMode.CLIENT_SERVER)
                    scaleInSharding2ClientServer();
                else if (runMode == RunMode.REPLICATION)
                    scaleInSharding2Replication();
                else if (runMode == RunMode.SHARDING)
                    sharding2Sharding();
            }
        }
        return 0;
    }

    private void alterDatabase() {
        if (runMode != null)
            db.setRunMode(runMode);
        if (parameters != null)
            db.alterParameters(parameters);
        if (replicationParameters != null)
            db.setReplicationParameters(replicationParameters);
        if (nodeAssignmentParameters != null)
            db.setNodeAssignmentParameters(nodeAssignmentParameters);
    }

    private void updateLocalMeta() {
        LealoneDatabase.getInstance().updateMeta(session, db);
    }

    private void updateRemoteNodes() {
        executeDatabaseStatement(db);
    }

    private void rewriteSql(boolean toReplicationMode) {
        selectNode();
        if (session.isRoot()) {
            oldHostIds = db.getHostIds();
            if (parameters != null && parameters.containsKey("hostIds")) {
                newHostIds = StringUtils.arraySplit(parameters.get("hostIds"), ',', true);
            } else {
                if (toReplicationMode)
                    newHostIds = NetNodeManagerHolder.get().getReplicationNodes(db);
                else
                    newHostIds = NetNodeManagerHolder.get().getShardingNodes(db);
            }

            String hostIds = StringUtils.arrayCombine(oldHostIds, ',') + ","
                    + StringUtils.arrayCombine(newHostIds, ',');
            db.getParameters().put("hostIds", hostIds);

            rewriteSql();
        } else {
            if (isTargetNode(db)) {
                oldHostIds = db.getHostIds();
                HashSet<String> oldSet = new HashSet<>(Arrays.asList(oldHostIds));
                if (parameters != null && parameters.containsKey("hostIds")) {
                    String[] hostIds = StringUtils.arraySplit(parameters.get("hostIds"), ',', true);
                    HashSet<String> newSet = new HashSet<>(Arrays.asList(hostIds));
                    newSet.removeAll(oldSet);
                    newHostIds = newSet.toArray(new String[0]);
                } else {
                    DbException.throwInternalError();
                }
            }
        }
    }

    private void rewriteSql() {
        StatementBuilder sql = new StatementBuilder("ALTER DATABASE ");
        sql.append(db.getShortName());
        sql.append(" RUN MODE ").append(runMode.toString());
        sql.append(" PARAMETERS");
        Database.appendMap(sql, db.getParameters());
        this.sql = sql.toString();
    }

    private void clientServer2ClientServer() {
        alterDatabase();
        updateLocalMeta();
        updateRemoteNodes();
    }

    private void replication2Replication() {
        int replicationFactorOld = getReplicationFactor(db.getReplicationParameters());
        int replicationFactorNew = getReplicationFactor(replicationParameters);
        int value = replicationFactorNew - replicationFactorOld;
        int replicationNodes = Math.abs(value);
        if (value > 0) {
            scaleOutReplication2Replication();
        } else if (value < 0) {
            scaleInReplication2Replication(replicationNodes);
        } else {
            alterDatabase();
            updateLocalMeta();
        }
    }

    private void sharding2Sharding() {
        int nodesOld = getNodes(db.getParameters());
        int nodesNew = getNodes(parameters);
        int value = nodesNew - nodesOld;
        // int nodes = Math.abs(value);
        if (value > 0) {
            scaleOutSharding2Sharding();
        } else if (value < 0) {
            scaleInSharding2Sharding(nodesNew);
        } else {
            alterDatabase();
            updateLocalMeta();
        }
    }

    private void selectNode() {
        // 由接入节点选择哪个节点作为发起数据复制操作的节点
        if (session.isRoot()) {
            String selectedNode = null;
            if (isTargetNode(db)) {
                selectedNode = db.getHostId(NetNode.getLocalP2pNode());
            } else {
                Set<NetNode> liveMembers = NetNodeManagerHolder.get().getLiveNodes();
                for (String hostId : db.getHostIds()) {
                    if (liveMembers.contains(db.getNode(hostId))) {
                        selectedNode = hostId;
                        break;
                    }
                }
            }
            if (selectedNode != null) {
                db.getParameters().put("_selectedNode_", selectedNode);
            }
        }
    }

    // 判断当前节点是否是发起数据复制操作的节点
    private boolean isSelectedNode(Database db) {
        // 如果当前节点是接入节点并且是数据库所在的目标节点之一，
        // 那么当前节点就会被选为发起数据复制操作的节点
        if (session.isRoot()) {
            if (isTargetNode(db)) {
                return true;
            } else {
                return false;
            }
        } else {
            // 看看当前节点是不是被选中的节点
            String selectedNode = parameters.get("_selectedNode_");
            if (selectedNode != null) {
                NetNode node = db.getNode(selectedNode);
                if (node.equals(NetNode.getLocalP2pNode())) {
                    return true;
                }
            }
            return false;
        }
    }

    // ----------------------scale out----------------------

    private void scaleOutClientServer2Replication() {
        alterDatabase();
        rewriteSql(true);
        updateLocalMeta();
        updateRemoteNodes();
        if (isSelectedNode(db)) {
            replicateTo(db, runMode, newHostIds);
        }
    }

    private void scaleOutClientServer2Sharding() {
        alterDatabase();
        rewriteSql(false);
        updateLocalMeta();
        updateRemoteNodes();
        if (isSelectedNode(db)) {
            sharding(db, runMode, oldHostIds, newHostIds);
        }
    }

    private void scaleOutReplication2Sharding() {
        alterDatabase();
        rewriteSql(false);
        updateLocalMeta();
        updateRemoteNodes();
        if (isSelectedNode(db)) {
            sharding(db, runMode, oldHostIds, newHostIds);
        }
    }

    private void scaleOutReplication2Replication() {
        alterDatabase();
        rewriteSql(true);
        updateLocalMeta();
        updateRemoteNodes();
        if (isSelectedNode(db)) {
            replicateTo(db, runMode, newHostIds);
        }
    }

    private void scaleOutSharding2Sharding() {
        alterDatabase();
        rewriteSql(false);
        updateLocalMeta();
        updateRemoteNodes();
        if (isSelectedNode(db)) {
            sharding(db, runMode, oldHostIds, newHostIds);
        }
    }

    // ----------------------scale in----------------------

    private void scaleInReplication2ClientServer() {
        scaleInReplication2Replication(db.getHostIds().length - 1);
    }

    private void scaleInReplication2Replication(int removeReplicationNodes) {
        alterDatabase();
        String[] removeHostIds = null;
        if (session.isRoot()) {
            oldHostIds = db.getHostIds();
            removeHostIds = new String[removeReplicationNodes];
            String[] newHostIds = new String[oldHostIds.length - removeReplicationNodes];
            System.arraycopy(oldHostIds, 0, removeHostIds, 0, removeReplicationNodes);
            System.arraycopy(oldHostIds, removeReplicationNodes, newHostIds, 0, newHostIds.length);

            db.getParameters().put("hostIds", StringUtils.arrayCombine(newHostIds, ','));
            db.getParameters().put("removeHostIds", StringUtils.arrayCombine(removeHostIds, ','));

            rewriteSql();
        }
        updateLocalMeta();
        updateRemoteNodes();
        Map<String, String> parameters = db.getParameters();
        if (parameters != null && parameters.containsKey("removeHostIds")) {
            removeHostIds = StringUtils.arraySplit(parameters.get("removeHostIds"), ',', true);
            HashSet<String> set = new HashSet<>(Arrays.asList(removeHostIds));
            NetNode localNode = NetNode.getLocalTcpNode();
            if (set.contains(localNode.getHostAndPort())) {
                LealoneDatabase lealoneDB = LealoneDatabase.getInstance();
                lealoneDB.removeDatabaseObject(session, db);
                db.setDeleteFilesOnDisconnect(true);
                if (db.getSessionCount() == 0) {
                    db.drop();
                }

                Database newDB = new Database(db.getId(), db.getShortName(), parameters);
                newDB.setReplicationParameters(replicationParameters);
                newDB.setRunMode(runMode);
                lealoneDB.addDatabaseObject(session, newDB);
                newDB.notifyRunModeChanged();
            }
        }
    }

    private void scaleInSharding2ClientServer() {
        scaleInSharding(1, RunMode.CLIENT_SERVER);
    }

    private void scaleInSharding2Replication() {
        int replicationFactor = getReplicationFactor(db.getReplicationParameters());
        if (replicationParameters != null) {
            int rf = getReplicationFactor(replicationParameters);
            if (rf < replicationFactor)
                replicationFactor = rf;
        }

        scaleInSharding(replicationFactor, RunMode.REPLICATION);
    }

    private void scaleInSharding2Sharding(int nodes) {
        scaleInSharding(nodes, RunMode.SHARDING);
    }

    private void scaleInSharding(int scaleInNodes, RunMode newRunMode) {
        RunMode oldRunMode = RunMode.SHARDING;
        String[] removeHostIds = null;
        alterDatabase();
        if (session.isRoot()) {
            oldHostIds = db.getHostIds();
            removeHostIds = new String[oldHostIds.length - scaleInNodes];
            String[] newHostIds = new String[scaleInNodes];
            System.arraycopy(oldHostIds, 0, removeHostIds, 0, removeHostIds.length);
            System.arraycopy(oldHostIds, removeHostIds.length, newHostIds, 0, scaleInNodes);

            db.getParameters().put("hostIds", StringUtils.arrayCombine(newHostIds, ','));
            db.getParameters().put("removeHostIds", StringUtils.arrayCombine(removeHostIds, ','));
            db.getParameters().put("nodes", newHostIds.length + "");
            rewriteSql();
        }
        updateLocalMeta();
        updateRemoteNodes();
        Map<String, String> parameters = db.getParameters();

        newHostIds = StringUtils.arraySplit(parameters.get("hostIds"), ',', true);
        HashSet<String> set;
        String localHostId = NetNode.getLocalTcpNode().getHostAndPort();
        if (parameters != null && parameters.containsKey("removeHostIds")) {
            removeHostIds = StringUtils.arraySplit(parameters.get("removeHostIds"), ',', true);
            set = new HashSet<>(Arrays.asList(removeHostIds));
            if (set.contains(localHostId)) {
                // TODO 等到数据迁移完成后再删
                // LealoneDatabase lealoneDB = LealoneDatabase.getInstance();
                // lealoneDB.removeDatabaseObject(session, db);
                // db.setDeleteFilesOnDisconnect(true);
                // if (db.getSessionCount() == 0) {
                // db.drop();
                // }
                //
                // Database newDB = new Database(db.getId(), db.getShortName(), parameters);
                // newDB.setReplicationProperties(replicationProperties);
                // newDB.setRunMode(runMode);
                // lealoneDB.addDatabaseObject(session, newDB);
            }
        }

        if (newRunMode == RunMode.SHARDING) {
            set = new HashSet<>(Arrays.asList(removeHostIds));
            if (set.contains(localHostId)) {
                scaleIn(db, oldRunMode, newRunMode, removeHostIds, newHostIds);
            }
        } else if (newRunMode == RunMode.CLIENT_SERVER || newRunMode == RunMode.REPLICATION) {
            set = new HashSet<>(Arrays.asList(newHostIds));
            if (set.contains(localHostId)) {
                scaleIn(db, oldRunMode, newRunMode, null, newHostIds);
            }
        }
    }

    private static int getReplicationFactor(Map<String, String> replicationProperties) {
        return getIntPropertyValue("replication_factor", replicationProperties);
    }

    private static int getNodes(Map<String, String> parameters) {
        return getIntPropertyValue("nodes", parameters);
    }

    private static int getIntPropertyValue(String key, Map<String, String> properties) {
        if (properties == null)
            return 0;
        String value = properties.get(key);
        if (value == null)
            return 0;
        return Integer.parseInt(value);
    }

    private static void scaleIn(Database db, RunMode oldRunMode, RunMode newRunMode, String[] oldNodes,
            String[] newNodes) {
        ConcurrentUtils.submitTask("ScaleIn Nodes", () -> {
            for (Storage storage : db.getStorages()) {
                storage.scaleIn(db, oldRunMode, newRunMode, oldNodes, newNodes);
            }
            db.notifyRunModeChanged();
        });
    }

    private static void replicateTo(Database db, RunMode newRunMode, String[] newReplicationNodes) {
        ConcurrentUtils.submitTask("Replicate Pages", () -> {
            // 先复制包含meta(sys)表的Storage
            Storage metaStorage = db.getMetaStorage();
            metaStorage.replicateTo(db, newReplicationNodes, newRunMode);
            List<Storage> storages = db.getStorages();
            storages.remove(metaStorage);
            for (Storage storage : storages) {
                storage.replicateTo(db, newReplicationNodes, newRunMode);
            }
            db.notifyRunModeChanged();
        });
    }

    private static void sharding(Database db, RunMode newRunMode, String[] oldNodes, String[] newNodes) {
        ConcurrentUtils.submitTask("Sharding Pages", () -> {
            for (Storage storage : db.getStorages()) {
                storage.sharding(db, oldNodes, newNodes, newRunMode);
            }
            db.notifyRunModeChanged();
        });
    }
}
