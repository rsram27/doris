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

package org.apache.doris.datasource;

import org.apache.doris.catalog.Type;
import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.Config;
import org.apache.doris.common.Pair;
import org.apache.doris.common.ThreadPoolManager;
import org.apache.doris.datasource.hive.HMSExternalCatalog;
import org.apache.doris.datasource.hive.HMSExternalTable;
import org.apache.doris.datasource.hive.HiveMetaStoreCache;
import org.apache.doris.datasource.hudi.source.HudiCachedFsViewProcessor;
import org.apache.doris.datasource.hudi.source.HudiCachedMetaClientProcessor;
import org.apache.doris.datasource.hudi.source.HudiMetadataCacheMgr;
import org.apache.doris.datasource.hudi.source.HudiPartitionProcessor;
import org.apache.doris.datasource.iceberg.IcebergMetadataCache;
import org.apache.doris.datasource.iceberg.IcebergMetadataCacheMgr;
import org.apache.doris.datasource.maxcompute.MaxComputeMetadataCache;
import org.apache.doris.datasource.maxcompute.MaxComputeMetadataCacheMgr;
import org.apache.doris.datasource.metacache.MetaCache;
import org.apache.doris.datasource.mvcc.MvccUtil;
import org.apache.doris.datasource.paimon.PaimonMetadataCache;
import org.apache.doris.datasource.paimon.PaimonMetadataCacheMgr;
import org.apache.doris.fs.FileSystemCache;
import org.apache.doris.nereids.exceptions.NotSupportedException;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;

/**
 * Cache meta of external catalog
 * 1. Meta for hive meta store, mainly for partition.
 * 2. Table Schema cache.
 * 3. Row count cache.
 */
public class ExternalMetaCacheMgr {
    private static final Logger LOG = LogManager.getLogger(ExternalMetaCacheMgr.class);

    /**
     * Executors for loading caches
     * 1. rowCountRefreshExecutor
     * For row count cache.
     * Row count cache is an async loading cache, and we can ignore the result
     * if cache missing or thread pool is full.
     * So use a separate executor for this cache.
     * <p>
     * 2.  commonRefreshExecutor
     * For other caches. Other caches are sync loading cache.
     * But commonRefreshExecutor will be used for async refresh.
     * That is, if cache entry is missing, the cache value will be loaded in caller thread, sychronously.
     * if cache entry need refresh, it will be reloaded in commonRefreshExecutor.
     * <p>
     * 3. fileListingExecutor
     * File listing is a heavy operation, so use a separate executor for it.
     * For fileCache, the refresh operation will still use commonRefreshExecutor to trigger refresh.
     * And fileListingExecutor will be used to list file.
     */
    private ExecutorService rowCountRefreshExecutor;
    private ExecutorService commonRefreshExecutor;
    private ExecutorService fileListingExecutor;
    // This executor is used to schedule the getting split tasks
    private ExecutorService scheduleExecutor;

    // catalog id -> HiveMetaStoreCache
    private final Map<Long, HiveMetaStoreCache> cacheMap = Maps.newConcurrentMap();
    // catalog id -> table schema cache
    private final Map<Long, ExternalSchemaCache> schemaCacheMap = Maps.newHashMap();
    // hudi partition manager
    private final HudiMetadataCacheMgr hudiMetadataCacheMgr;
    // all catalogs could share the same fsCache.
    private FileSystemCache fsCache;
    // all external table row count cache.
    private ExternalRowCountCache rowCountCache;
    private final IcebergMetadataCacheMgr icebergMetadataCacheMgr;
    private final MaxComputeMetadataCacheMgr maxComputeMetadataCacheMgr;
    private final PaimonMetadataCacheMgr paimonMetadataCacheMgr;

    public ExternalMetaCacheMgr(boolean isCheckpointCatalog) {
        rowCountRefreshExecutor = newThreadPool(isCheckpointCatalog,
                Config.max_external_cache_loader_thread_pool_size,
                Config.max_external_cache_loader_thread_pool_size * 1000,
                "RowCountRefreshExecutor", 0, true);

        commonRefreshExecutor = newThreadPool(isCheckpointCatalog,
                Config.max_external_cache_loader_thread_pool_size,
                Config.max_external_cache_loader_thread_pool_size * 10000,
                "CommonRefreshExecutor", 10, true);

        // The queue size should be large enough,
        // because there may be thousands of partitions being queried at the same time.
        fileListingExecutor = newThreadPool(isCheckpointCatalog,
                Config.max_external_cache_loader_thread_pool_size,
                Config.max_external_cache_loader_thread_pool_size * 1000,
                "FileListingExecutor", 10, true);

        scheduleExecutor = newThreadPool(isCheckpointCatalog,
                Config.max_external_cache_loader_thread_pool_size,
                Config.max_external_cache_loader_thread_pool_size * 1000,
                "scheduleExecutor", 10, true);

        fsCache = new FileSystemCache();
        rowCountCache = new ExternalRowCountCache(rowCountRefreshExecutor);

        hudiMetadataCacheMgr = new HudiMetadataCacheMgr(commonRefreshExecutor);
        icebergMetadataCacheMgr = new IcebergMetadataCacheMgr(commonRefreshExecutor);
        maxComputeMetadataCacheMgr = new MaxComputeMetadataCacheMgr();
        paimonMetadataCacheMgr = new PaimonMetadataCacheMgr(commonRefreshExecutor);
    }

    private ExecutorService newThreadPool(boolean isCheckpointCatalog, int numThread, int queueSize,
            String poolName, int timeoutSeconds,
            boolean needRegisterMetric) {
        String executorNamePrefix = isCheckpointCatalog ? "Checkpoint" : "NotCheckpoint";
        String realPoolName = executorNamePrefix + poolName;
        // Business threads require a fixed size thread pool and use queues to store unprocessed tasks.
        // Checkpoint threads have almost no business and need to be released in a timely manner to avoid thread leakage
        if (isCheckpointCatalog) {
            return ThreadPoolManager.newDaemonCacheThreadPool(numThread, realPoolName, needRegisterMetric);
        } else {
            return ThreadPoolManager.newDaemonFixedThreadPool(numThread, queueSize, realPoolName, timeoutSeconds,
                    needRegisterMetric);
        }
    }

    public ExecutorService getFileListingExecutor() {
        return fileListingExecutor;
    }

    public ExecutorService getScheduleExecutor() {
        return scheduleExecutor;
    }

    public HiveMetaStoreCache getMetaStoreCache(HMSExternalCatalog catalog) {
        HiveMetaStoreCache cache = cacheMap.get(catalog.getId());
        if (cache == null) {
            synchronized (cacheMap) {
                if (!cacheMap.containsKey(catalog.getId())) {
                    cacheMap.put(catalog.getId(),
                            new HiveMetaStoreCache(catalog, commonRefreshExecutor, fileListingExecutor));
                }
                cache = cacheMap.get(catalog.getId());
            }
        }
        return cache;
    }

    public ExternalSchemaCache getSchemaCache(ExternalCatalog catalog) {
        ExternalSchemaCache cache = schemaCacheMap.get(catalog.getId());
        if (cache == null) {
            synchronized (schemaCacheMap) {
                if (!schemaCacheMap.containsKey(catalog.getId())) {
                    schemaCacheMap.put(catalog.getId(), new ExternalSchemaCache(catalog, commonRefreshExecutor));
                }
                cache = schemaCacheMap.get(catalog.getId());
            }
        }
        return cache;
    }

    public HudiPartitionProcessor getHudiPartitionProcess(ExternalCatalog catalog) {
        return hudiMetadataCacheMgr.getPartitionProcessor(catalog);
    }

    public HudiCachedFsViewProcessor getFsViewProcessor(ExternalCatalog catalog) {
        return hudiMetadataCacheMgr.getFsViewProcessor(catalog);
    }

    public HudiCachedMetaClientProcessor getMetaClientProcessor(ExternalCatalog catalog) {
        return hudiMetadataCacheMgr.getHudiMetaClientProcessor(catalog);
    }

    public HudiMetadataCacheMgr getHudiMetadataCacheMgr() {
        return hudiMetadataCacheMgr;
    }

    public IcebergMetadataCache getIcebergMetadataCache() {
        return icebergMetadataCacheMgr.getIcebergMetadataCache();
    }

    public PaimonMetadataCache getPaimonMetadataCache() {
        return paimonMetadataCacheMgr.getPaimonMetadataCache();
    }

    public MaxComputeMetadataCache getMaxComputeMetadataCache(long catalogId) {
        return maxComputeMetadataCacheMgr.getMaxComputeMetadataCache(catalogId);
    }

    public FileSystemCache getFsCache() {
        return fsCache;
    }

    public ExternalRowCountCache getRowCountCache() {
        return rowCountCache;
    }

    public void removeCache(long catalogId) {
        if (cacheMap.remove(catalogId) != null) {
            LOG.info("remove hive metastore cache for catalog {}", catalogId);
        }
        synchronized (schemaCacheMap) {
            if (schemaCacheMap.remove(catalogId) != null) {
                LOG.info("remove schema cache for catalog {}", catalogId);
            }
        }
        hudiMetadataCacheMgr.removeCache(catalogId);
        icebergMetadataCacheMgr.removeCache(catalogId);
        maxComputeMetadataCacheMgr.removeCache(catalogId);
        paimonMetadataCacheMgr.removeCache(catalogId);
    }

    public void invalidateTableCache(ExternalTable dorisTable) {
        synchronized (schemaCacheMap) {
            ExternalSchemaCache schemaCache = schemaCacheMap.get(dorisTable.getCatalog().getId());
            if (schemaCache != null) {
                schemaCache.invalidateTableCache(dorisTable);
            }
        }
        HiveMetaStoreCache metaCache = cacheMap.get(dorisTable.getCatalog().getId());
        if (metaCache != null) {
            metaCache.invalidateTableCache(dorisTable.getOrBuildNameMapping());
        }
        hudiMetadataCacheMgr.invalidateTableCache(dorisTable);
        icebergMetadataCacheMgr.invalidateTableCache(dorisTable);
        maxComputeMetadataCacheMgr.invalidateTableCache(dorisTable);
        paimonMetadataCacheMgr.invalidateTableCache(dorisTable);
        if (LOG.isDebugEnabled()) {
            LOG.debug("invalid table cache for {}.{} in catalog {}", dorisTable.getRemoteDbName(),
                    dorisTable.getRemoteName(), dorisTable.getCatalog().getName());
        }
    }

    public void invalidateDbCache(long catalogId, String dbName) {
        dbName = ClusterNamespace.getNameFromFullName(dbName);
        synchronized (schemaCacheMap) {
            ExternalSchemaCache schemaCache = schemaCacheMap.get(catalogId);
            if (schemaCache != null) {
                schemaCache.invalidateDbCache(dbName);
            }
        }
        HiveMetaStoreCache metaCache = cacheMap.get(catalogId);
        if (metaCache != null) {
            metaCache.invalidateDbCache(dbName);
        }
        hudiMetadataCacheMgr.invalidateDbCache(catalogId, dbName);
        icebergMetadataCacheMgr.invalidateDbCache(catalogId, dbName);
        maxComputeMetadataCacheMgr.invalidateDbCache(catalogId, dbName);
        paimonMetadataCacheMgr.invalidateDbCache(catalogId, dbName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("invalid db cache for {} in catalog {}", dbName, catalogId);
        }
    }

    public void invalidateCatalogCache(long catalogId) {
        synchronized (schemaCacheMap) {
            schemaCacheMap.remove(catalogId);
        }
        HiveMetaStoreCache metaCache = cacheMap.get(catalogId);
        if (metaCache != null) {
            metaCache.invalidateAll();
        }
        hudiMetadataCacheMgr.invalidateCatalogCache(catalogId);
        icebergMetadataCacheMgr.invalidateCatalogCache(catalogId);
        maxComputeMetadataCacheMgr.invalidateCatalogCache(catalogId);
        paimonMetadataCacheMgr.invalidateCatalogCache(catalogId);
        if (LOG.isDebugEnabled()) {
            LOG.debug("invalid catalog cache for {}", catalogId);
        }
    }

    public void invalidSchemaCache(long catalogId) {
        synchronized (schemaCacheMap) {
            schemaCacheMap.remove(catalogId);
        }
    }

    public void addPartitionsCache(long catalogId, HMSExternalTable table, List<String> partitionNames) {
        String dbName = ClusterNamespace.getNameFromFullName(table.getDbName());
        HiveMetaStoreCache metaCache = cacheMap.get(catalogId);
        if (metaCache != null) {
            List<Type> partitionColumnTypes;
            try {
                partitionColumnTypes = table.getPartitionColumnTypes(MvccUtil.getSnapshotFromContext(table));
            } catch (NotSupportedException e) {
                LOG.warn("Ignore not supported hms table, message: {} ", e.getMessage());
                return;
            }
            metaCache.addPartitionsCache(table.getOrBuildNameMapping(), partitionNames, partitionColumnTypes);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("add partition cache for {}.{} in catalog {}", dbName, table.getName(), catalogId);
        }
    }

    public void dropPartitionsCache(long catalogId, HMSExternalTable table, List<String> partitionNames) {
        String dbName = ClusterNamespace.getNameFromFullName(table.getDbName());
        HiveMetaStoreCache metaCache = cacheMap.get(catalogId);
        if (metaCache != null) {
            metaCache.dropPartitionsCache(table, partitionNames, true);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("drop partition cache for {}.{} in catalog {}", dbName, table.getName(), catalogId);
        }
    }

    public void invalidatePartitionsCache(ExternalTable dorisTable, List<String> partitionNames) {
        HiveMetaStoreCache metaCache = cacheMap.get(dorisTable.getCatalog().getId());
        if (metaCache != null) {
            for (String partitionName : partitionNames) {
                metaCache.invalidatePartitionCache(dorisTable, partitionName);
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("invalidate partition cache for {}.{} in catalog {}",
                    dorisTable.getDbName(), dorisTable.getName(), dorisTable.getCatalog().getName());
        }
    }

    public <T> MetaCache<T> buildMetaCache(String name,
            OptionalLong expireAfterAccessSec, OptionalLong refreshAfterWriteSec, long maxSize,
            CacheLoader<String, List<Pair<String, String>>> namesCacheLoader,
            CacheLoader<String, Optional<T>> metaObjCacheLoader,
            RemovalListener<String, Optional<T>> removalListener) {
        MetaCache<T> metaCache = new MetaCache<>(
                name, commonRefreshExecutor, expireAfterAccessSec, refreshAfterWriteSec,
                maxSize, namesCacheLoader, metaObjCacheLoader, removalListener);
        return metaCache;
    }

    public static Map<String, String> getCacheStats(CacheStats cacheStats, long estimatedSize) {
        Map<String, String> stats = Maps.newHashMap();
        stats.put("hit_ratio", String.valueOf(cacheStats.hitRate()));
        stats.put("hit_count", String.valueOf(cacheStats.hitCount()));
        stats.put("read_count", String.valueOf(cacheStats.hitCount() + cacheStats.missCount()));
        stats.put("eviction_count", String.valueOf(cacheStats.evictionCount()));
        stats.put("average_load_penalty", String.valueOf(cacheStats.averageLoadPenalty()));
        stats.put("estimated_size", String.valueOf(estimatedSize));
        return stats;
    }
}
