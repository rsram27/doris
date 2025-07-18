// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.cluster;

import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.MaterializedIndex;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.Replica;
import org.apache.doris.catalog.Tablet;
import org.apache.doris.catalog.TabletInvertedIndex;
import org.apache.doris.catalog.TabletMeta;
import org.apache.doris.clone.RebalancerTestUtil;
import org.apache.doris.common.Config;
import org.apache.doris.common.FeConstants;
import org.apache.doris.nereids.trees.plans.PlanType;
import org.apache.doris.nereids.trees.plans.commands.AlterSystemCommand;
import org.apache.doris.nereids.trees.plans.commands.info.DecommissionBackendOp;
import org.apache.doris.qe.StmtExecutor;
import org.apache.doris.system.Backend;
import org.apache.doris.thrift.TStorageMedium;
import org.apache.doris.utframe.TestWithFeService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

public class DecommissionBackendTest extends TestWithFeService {
    @Override
    protected int backendNum() {
        return 4;
    }

    @Override
    protected void beforeCluster() {
        FeConstants.runningUnitTest = true;
    }

    @BeforeAll
    public void beforeClass() {
        FeConstants.runningUnitTest = true;
    }

    @Override
    protected void beforeCreatingConnectContext() throws Exception {
        FeConstants.default_scheduler_interval_millisecond = 100;
        Config.tablet_checker_interval_ms = 100;
        Config.tablet_schedule_interval_ms = 100;
        Config.tablet_repair_delay_factor_second = 1;
        Config.allow_replica_on_same_host = true;
        Config.disable_balance = true;
        Config.schedule_batch_size = 1000;
        Config.schedule_slot_num_per_hdd_path = 1000;
        Config.heartbeat_interval_second = 5;
    }

    @Test
    public void testDecommissionBackend() throws Exception {
        // 1. create connect context
        connectContext = createDefaultCtx();

        ImmutableMap<Long, Backend> idToBackendRef = Env.getCurrentSystemInfo().getAllBackendsByAllCluster();
        Assertions.assertEquals(backendNum(), idToBackendRef.size());

        // 2. create database db1
        createDatabase("db1");
        System.out.println(Env.getCurrentInternalCatalog().getDbNames());

        // 3. create table tbl1
        createTable("create table db1.tbl1(k1 int) distributed by hash(k1) buckets 3 properties('replication_num' = '1');");
        RebalancerTestUtil.updateReplicaPathHash();

        // 4. query tablet num
        int tabletNum = Env.getCurrentInvertedIndex().getTabletMetaMap().size();

        // 5. execute decommission
        Backend srcBackend = null;
        for (Backend backend : idToBackendRef.values()) {
            if (!Env.getCurrentInvertedIndex().getTabletIdsByBackendId(backend.getId()).isEmpty()) {
                srcBackend = backend;
                break;
            }
        }

        Assertions.assertNotNull(srcBackend);
        // "alter system decommission backend \"" + srcBackend.getAddress() + "\"";
        String hostPort = srcBackend.getHost() + ":" + srcBackend.getHeartbeatPort();
        DecommissionBackendOp op = new DecommissionBackendOp(ImmutableList.of(hostPort));
        AlterSystemCommand command = new AlterSystemCommand(op, PlanType.ALTER_SYSTEM_DECOMMISSION_BACKEND);
        command.doRun(connectContext, new StmtExecutor(connectContext, ""));

        Assertions.assertTrue(srcBackend.isDecommissioned());
        long startTimestamp = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTimestamp < 90000
            && Env.getCurrentSystemInfo().getAllBackendsByAllCluster().containsKey(srcBackend.getId())) {
            Thread.sleep(1000);
        }

        Assertions.assertEquals(backendNum() - 1, Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

        // For now, we have pre-built internal table: analysis_job and column_statistics
        Assertions.assertEquals(tabletNum,
                Env.getCurrentInvertedIndex().getTabletMetaMap().size());

        // 6. add backend
        addNewBackend();
        Assertions.assertEquals(backendNum(), Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());
    }

    @Test
    public void testDecommissionBackendById() throws Exception {
        // 1. create connect context
        connectContext = createDefaultCtx();
        ImmutableMap<Long, Backend> idToBackendRef = Env.getCurrentSystemInfo().getAllBackendsByAllCluster();
        Assertions.assertEquals(backendNum(), idToBackendRef.size());

        // 2. create database db1
        createDatabase("db2");
        System.out.println(Env.getCurrentInternalCatalog().getDbNames());

        // 3. create table tbl1
        createTable("create table db2.tbl1(k1 int) distributed by hash(k1) buckets 3 properties('replication_num' = '1');");
        RebalancerTestUtil.updateReplicaPathHash();

        // 4. query tablet num
        int tabletNum = Env.getCurrentInvertedIndex().getTabletMetaMap().size();
        Assertions.assertTrue(tabletNum > 0);

        // 5. execute decommission
        Backend srcBackend = null;
        for (Backend backend : idToBackendRef.values()) {
            if (!Env.getCurrentInvertedIndex().getTabletIdsByBackendId(backend.getId()).isEmpty()) {
                srcBackend = backend;
                break;
            }
        }

        Assertions.assertNotNull(srcBackend);

        // decommission backend by id
        // "alter system decommission backend \"" + srcBackend.getId() + "\"";
        String hostPort = srcBackend.getHost() + ":" + srcBackend.getHeartbeatPort();
        DecommissionBackendOp op = new DecommissionBackendOp(ImmutableList.of(hostPort));
        AlterSystemCommand command = new AlterSystemCommand(op, PlanType.ALTER_SYSTEM_DECOMMISSION_BACKEND);
        command.doRun(connectContext, new StmtExecutor(connectContext, ""));

        Assertions.assertTrue(srcBackend.isDecommissioned());
        long startTimestamp = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTimestamp < 90000
                && Env.getCurrentSystemInfo().getAllBackendsByAllCluster().containsKey(srcBackend.getId())) {
            Thread.sleep(1000);
        }

        Assertions.assertEquals(backendNum() - 1, Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

        // add backend
        addNewBackend();
        Assertions.assertEquals(backendNum(), Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

    }

    @Test
    public void testDecommissionBackendWithMTMV() throws Exception {
        // 1. create connect context
        connectContext = createDefaultCtx();

        ImmutableMap<Long, Backend> idToBackendRef = Env.getCurrentSystemInfo().getAllBackendsByAllCluster();
        Assertions.assertEquals(backendNum(), idToBackendRef.size());

        // 2. create database db1
        createDatabase("db4");
        System.out.println(Env.getCurrentInternalCatalog().getDbNames());

        // 3. create table
        createTable("CREATE TABLE db4.table1 (\n"
                + " `c1` varchar(20) NULL,\n"
                + " `c2` bigint(20) NULL,\n"
                + " `c3` int(20) not NULL,\n"
                + " `k4` bitmap BITMAP_UNION,\n"
                + " `k5` bitmap BITMAP_UNION\n"
                + ") ENGINE=OLAP\n"
                + "AGGREGATE KEY(`c1`, `c2`, `c3`)\n"
                + "COMMENT 'OLAP'\n"
                + "DISTRIBUTED BY HASH(`c2`) BUCKETS 1\n"
                + ";");

        createTable("CREATE TABLE db4.table2 (\n"
                + " `c1` bigint(20) NULL,\n"
                + " `c2` bigint(20) NULL,\n"
                + " `c3` bigint(20) not NULL,\n"
                + " `k4` bitmap BITMAP_UNION,\n"
                + " `k5` bitmap BITMAP_UNION\n"
                + ") ENGINE=OLAP\n"
                + "AGGREGATE KEY(`c1`, `c2`, `c3`)\n"
                + "COMMENT 'OLAP'\n"
                + "DISTRIBUTED BY HASH(`c2`) BUCKETS 1\n"
                + ";");

        createMvByNereids("create materialized view db4.mv1 BUILD DEFERRED REFRESH COMPLETE ON MANUAL\n"
                + "DISTRIBUTED BY RANDOM BUCKETS 1\n"
                + "as "
                + "select t1.c1, t2.c2, t2.k4 "
                + "from db4.table1 t1 "
                + "inner join db4.table2 t2 on t1.c1= t2.c2;");

        createMvByNereids("create materialized view db4.mv2 BUILD DEFERRED REFRESH COMPLETE ON MANUAL\n"
                + "DISTRIBUTED BY HASH(c1) BUCKETS 20 \n"
                + "PROPERTIES ( 'colocate_with' = 'foo', 'replication_num' = '3' ) "
                + "as "
                + "select t1.c1 as c1, t2.c3, t2.k5 "
                + "from db4.table1 t1 "
                + "inner join db4.table2 t2 on t1.c1= t2.c3;");

        RebalancerTestUtil.updateReplicaPathHash();

        Database db = Env.getCurrentInternalCatalog().getDbOrMetaException("db4");
        OlapTable tbl = (OlapTable) db.getTableOrMetaException("mv1");
        Assertions.assertNotNull(tbl);

        Partition partition = tbl.getPartitions().iterator().next();
        Tablet tablet = partition.getMaterializedIndices(MaterializedIndex.IndexExtState.ALL)
                .iterator().next().getTablets().iterator().next();
        Assertions.assertNotNull(tablet);
        Backend srcBackend = Env.getCurrentSystemInfo().getBackend(tablet.getReplicas().get(0).getBackendId());
        Assertions.assertNotNull(srcBackend);

        // 4. query tablet num
        int tabletNum = Env.getCurrentInvertedIndex().getTabletMetaMap().size();

        // "alter system decommission backend \"" + srcBackend.getAddress() + "\"";
        String hostPort = srcBackend.getHost() + ":" + srcBackend.getHeartbeatPort();
        DecommissionBackendOp op = new DecommissionBackendOp(ImmutableList.of(hostPort));
        AlterSystemCommand command = new AlterSystemCommand(op, PlanType.ALTER_SYSTEM_DECOMMISSION_BACKEND);
        command.doRun(connectContext, new StmtExecutor(connectContext, ""));

        Assertions.assertTrue(srcBackend.isDecommissioned());
        long startTimestamp = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTimestamp < 90000
            && Env.getCurrentSystemInfo().getAllBackendsByAllCluster().containsKey(srcBackend.getId())) {
            Thread.sleep(1000);
        }

        Assertions.assertEquals(backendNum() - 1, Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

        // For now, we have pre-built internal table: analysis_job and column_statistics
        Assertions.assertEquals(tabletNum,
                Env.getCurrentInvertedIndex().getTabletMetaMap().size());

        for (Replica replica : tablet.getReplicas()) {
            Assertions.assertTrue(replica.getBackendId() != srcBackend.getId());
        }

        // 6. add backend
        addNewBackend();
        Assertions.assertEquals(backendNum(), Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

        dropDatabase("db4");
    }

    @Test
    public void testDecommissionBackendWithLeakyTablets() throws Exception {
        // 1. create connect context
        connectContext = createDefaultCtx();

        Assertions.assertEquals(backendNum(), Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

        // 2. create database db5
        createDatabase("db5");
        System.out.println(Env.getCurrentInternalCatalog().getDbNames());

        // 3. create table tbl1
        createTable("create table db5.tbl1(k1 int) distributed by hash(k1) buckets 3 properties('replication_num' = '1');");
        RebalancerTestUtil.updateReplicaPathHash();

        Database db = Env.getCurrentInternalCatalog().getDbOrMetaException("db5");
        OlapTable tbl = (OlapTable) db.getTableOrMetaException("tbl1");
        Assertions.assertNotNull(tbl);

        Partition partition = tbl.getPartitions().iterator().next();
        Tablet tablet = partition.getMaterializedIndices(MaterializedIndex.IndexExtState.ALL)
                .iterator().next().getTablets().iterator().next();
        Assertions.assertNotNull(tablet);
        Backend srcBackend = Env.getCurrentSystemInfo().getBackend(tablet.getReplicas().get(0).getBackendId());
        Assertions.assertNotNull(srcBackend);

        TabletInvertedIndex invertIndex = Env.getCurrentInvertedIndex();
        long fakeTabletId =  123123123L;
        TabletMeta fakeTabletMeta = new TabletMeta(1234567L, 1234568L, 1234569L, 1234570L, 0, TStorageMedium.HDD);
        Replica fakeReplica = new Replica(1234571L, srcBackend.getId(), 0, Replica.ReplicaState.NORMAL);

        Supplier<List<Long>> getNotInRecycleBinTablets = () -> {
            List<Long> tabletIds = Lists.newArrayList();
            for (long tabletId : invertIndex.getTabletIdsByBackendId(srcBackend.getId())) {
                TabletMeta tabletMeta = invertIndex.getTabletMeta(tabletId);
                if (tabletMeta == null || !Env.getCurrentRecycleBin().isRecyclePartition(
                        tabletMeta.getDbId(), tabletMeta.getTableId(), tabletMeta.getPartitionId())) {
                    tabletIds.add(tabletId);
                }
            }
            return tabletIds;
        };
        try {
            Config.decommission_skip_leaky_tablet_second = 3600;

            // add leaky tablet
            invertIndex.addTablet(fakeTabletId, fakeTabletMeta);
            invertIndex.addReplica(fakeTabletId, fakeReplica);

            // "alter system decommission backend \"" + srcBackend.getAddress() + "\"";
            String hostPort = srcBackend.getHost() + ":" + srcBackend.getHeartbeatPort();
            DecommissionBackendOp op = new DecommissionBackendOp(ImmutableList.of(hostPort));
            AlterSystemCommand command = new AlterSystemCommand(op, PlanType.ALTER_SYSTEM_DECOMMISSION_BACKEND);
            command.doRun(connectContext, new StmtExecutor(connectContext, ""));

            Assertions.assertTrue(srcBackend.isDecommissioned());

            long startTimestamp = System.currentTimeMillis();

            List<Long> expectTabletIds = Lists.newArrayList(fakeTabletId);
            while (System.currentTimeMillis() - startTimestamp < 90000
                    && !expectTabletIds.equals(getNotInRecycleBinTablets.get())) {
                Thread.sleep(1000);
            }

            Assertions.assertEquals(expectTabletIds, getNotInRecycleBinTablets.get());
            Thread.sleep(5000);
            Assertions.assertEquals(expectTabletIds, getNotInRecycleBinTablets.get());
            Assertions.assertNotNull(Env.getCurrentSystemInfo().getBackend(srcBackend.getId()));
            // skip leaky tablets, decommission succ
            Config.decommission_skip_leaky_tablet_second = 1;
            Thread.sleep(4000);
            Assertions.assertNull(Env.getCurrentSystemInfo().getBackend(srcBackend.getId()));
        } finally {
            invertIndex.deleteTablet(fakeTabletId);
        }

        Assertions.assertEquals(backendNum() - 1, Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

        // 6. add backend
        addNewBackend();
        Assertions.assertEquals(backendNum(), Env.getCurrentSystemInfo().getAllBackendsByAllCluster().size());

        dropDatabase("db5");
    }

}
