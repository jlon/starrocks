# OPPO StarRocks Change Log

This document tracks OPPO-owned changes on top of StarRocks 4.1.x.
Keep it updated when adding, backporting, or porting a fork-only change.

## Scope

- Workspace: `/mnt/data/starrocks`
- Current branch: `branch-4.1.1`
- Audit date: 2026-07-04
- Upstream comparison ref: `upstream/branch-4.1.1`
- Upstream ref commit: `14b7e3fa6626a9959179d1b4442d021ce1dd895f`
- Local range audited: `upstream/branch-4.1.1..46af8f3929f2e1e870931b0fe920854729d2a9bb`
- Local range size at audit time: 123 commits

Evidence commands:

```bash
git rev-parse --verify upstream/branch-4.1.1
git rev-list --count upstream/branch-4.1.1..46af8f3929f2e1e870931b0fe920854729d2a9bb
git log --format='%H%x09%h%x09%an <%ae>%x09%s' \
  upstream/branch-4.1.1..46af8f3929f2e1e870931b0fe920854729d2a9bb \
  --author='oppo.com\|adc.com'
```

## OPPO-Owned Commits On branch-4.1.1

These commits are in the audited local range and have OPPO author evidence.
Treat them as fork-owned changes when moving to another StarRocks branch.

| Commit | Author | Area | Purpose |
| --- | --- | --- | --- |
| `97a036826d420d1e19569ff0c1bdc04a6aee94a2` | `jianglong@oppo.com` | BE cache | Avoid filling data cache from metadata/stat RPCs. |
| `b498560f5d0b154e2e8904b255bea4a6f4e6d695` | `jianglong@oppo.com` | FE shared-data cleanup | Protect StarMgr metadata cleanup in shared-data mode. |
| `f7339f03647b4b60aa3580ffdc8c57993e57ed75` | `qiunan1@oppo.com` | Shield plugin | Enforce Shield permissions at query time through catalog properties. |
| `36c1baa2c92e1a3c24daf1aa5fb50971c82e0c36` | `qiunan1@oppo.com` | Deploy, FE/CN image, Jindo, Shield | Add unified FE/CN image pipeline, Jindo OSS/Hadoop native support, CN entrypoint fix, libthrift update, and Shield/Jindo staging. |
| `32a179b28b5596fb6803069b442bed7b66590ce5` | `qiunan1@oppo.com` | Shield auth | Enable `authentication_shield_shared` shared-password login. |
| `b41d445f348bffce97ecac1309ec971ef9b86b3e` | `qiunan1@oppo.com` | FE entrypoint, Shield auth | Expand `meta_dir` with `POD_NAME`, resolve Leader host from `SHOW FRONTENDS`, and rebuild Shield shared-auth config after Gson load. |
| `3439a375bd26a96691603cfdec0ee6cc3c8dda63` | `jianglong@oppo.com` | BE lake vacuum | Avoid aborting on malformed txn log filenames. |
| `bf21f562e914918721285418bd4ccb526c5fda4f` | `jianglong@oppo.com` | FE lake tablet stats | Add parameter-gated parallel lake tablet stat collection. |
| `ab4e78dbcbbefd9a99e5e2cb003fdf05e9ccbe59` | `jianglong@oppo.com` | Docs | Track OPPO fork changes. |
| `6fcba194b3ef1ace8ed024dc6d94359decca4da3` | `jianglong@oppo.com` | FE lake tablet stats | Preserve parallel collector failure semantics and add serial/parallel correctness verification. |
| `57ef05bf9e65092a9a7336fe1655d3c73f146182` | `jianglong@oppo.com` | Docs | Update OPPO change log for tablet stat fixes. |
| `b00d41c9e32dd4525d80bf45a543cf9e2822c1e9` | `jianglong@oppo.com` | FE lake tablet stats | Reuse the lake tablet stat executor across rounds and harden stop/cancel lifecycle. |
| `6bf5e3f8e867d1d0dea2c8817726442c27644887` | `jianglong@oppo.com` | Docs | Track lake tablet stat executor reuse. |
| `00935906a808a5f80f973f1c919b43abc14749f5` | `jianglong@oppo.com` | Docs | Record FE replacement artifact. |
| `59c3b1750b3d9c51ddae263f2f2eaeefbabc7759` | `jianglong@oppo.com` | FE build compatibility | Fix range-distribution and Shield plugin API/build compatibility on 4.1.1. |
| `46af8f3929f2e1e870931b0fe920854729d2a9bb` | `jianglong@oppo.com` | BE lake tablet creation, FE lake rollback, Shield plugin | Add lake tablet metadata read-back verification, delete StarOS shards during FE rollback, and align Shield plugin APIs. |

## Commit Details

This section records what each OPPO-owned code commit changes. Evidence comes
from `git show --name-only <commit>` and the current source files listed below.

| Commit | What changed | Operational impact |
| --- | --- | --- |
| `97a036826d420d1e19569ff0c1bdc04a6aee94a2` | Changes BE lake metadata/stat RPC paths in `be/src/service/service_be/lake_service.cpp`, process setup in `be/src/service/starrocks_main.cpp`, and thrift handling in `be/src/util/thrift_util.cpp`. | Metadata/stat RPCs avoid filling the data cache. Runtime replacement: `be/lib/starrocks_be`. |
| `b498560f5d0b154e2e8904b255bea4a6f4e6d695` | Adds StarMgr cleanup throttles and abort controls in `Config.java`; updates `StarMgrMetaSyncer`, `CatalogRecycleBin`, colocate metadata, cluster snapshot classes, metrics, tests, and monitoring docs. | Shared-data metadata cleanup can be bounded by shard count, group count, runtime, and abort flag. Runtime replacement: `fe/lib/fe-core-4.1.1.jar`. |
| `f7339f03647b4b60aa3580ffdc8c57993e57ed75` | Adds `fe-plugin-shield`, `AccessControllerLoader`, and catalog-level access-controller binding through `AccessControlProvider` and `CatalogMgr`; adds Shield API client, RPD parser, permission checker, cache, timing log, and tests. | Hive external catalog permission checks can call Shield at query time from catalog properties. Runtime replacement: `fe/lib/fe-core-4.1.1.jar` and `fe/lib/fe-plugin-shield-1.0.0.jar`. |
| `36c1baa2c92e1a3c24daf1aa5fb50971c82e0c36` | Adds deploy image pipeline under `deploy/`, FE/CN Dockerfiles and entrypoints, Jindo jars, Hadoop native fetch script, Shield mock/test scripts, root `build.sh` staging, `start_fe.sh`/`start_backend.sh` classpath changes, CN `--cn` startup fix, and libthrift 0.23 adaptation. | Produces unified FE/CN images and stages Shield/Jindo assets. Runtime impact depends on image packaging files plus FE/BE artifacts. |
| `32a179b28b5596fb6803069b442bed7b66590ce5` | Adds Shield shared-password authentication type through `AuthenticationMgr`, `SecurityIntegrationFactory`, `PluginSecurityIntegrationSupport`, `AuthPlugin`, `ShowExecutor`, analyzer validation, persistence registration, and Shield auth classes/tests. | `authentication_shield_shared` users can log in without `CREATE USER` when the security integration is in `authentication_chain`. Runtime replacement: `fe/lib/fe-core-4.1.1.jar` and `fe/lib/fe-plugin-shield-1.0.0.jar`. |
| `b41d445f348bffce97ecac1309ec971ef9b86b3e` | Updates FE entrypoint to expand `meta_dir` with `POD_NAME`; updates Shield shared-auth config rebuild after Gson load; adjusts the example SQL. | FE pods avoid sharing a metadata directory, and Shield shared auth survives FE restart. Runtime replacement: FE image entrypoint and `fe-plugin-shield-1.0.0.jar`. |
| `3439a375bd26a96691603cfdec0ee6cc3c8dda63` | Changes `parse_txn_log_filename` handling in `be/src/storage/lake/filenames.h` and lake `vacuum.cpp`; adds `filenames_test.cpp` and `vacuum_test.cpp`. | Malformed txn log names no longer trigger a `CHECK` abort during vacuum. Runtime replacement: `be/lib/starrocks_be`. |
| `bf21f562e914918721285418bd4ccb526c5fda4f` | Adds parameter-gated parallel lake tablet stat collection in `TabletStatMgr`; adds FE configs and tests. | Shared-data tablet stat collection can run per partition in parallel when enabled. Runtime replacement: `fe/lib/fe-core-4.1.1.jar`. |
| `ab4e78dbcbbefd9a99e5e2cb003fdf05e9ccbe59` | Creates this OPPO change log. | Docs only. |
| `6fcba194b3ef1ace8ed024dc6d94359decca4da3` | Fixes `TabletStatMgr` parallel collector semantics and tests serial/parallel correctness. | Parallel tablet stat collection preserves original failure behavior. Runtime replacement: `fe/lib/fe-core-4.1.1.jar`. |
| `57ef05bf9e65092a9a7336fe1655d3c73f146182` | Updates this change log for tablet stat fixes. | Docs only. |
| `b00d41c9e32dd4525d80bf45a543cf9e2822c1e9` | Reuses the lake tablet stat executor across rounds, hardens stop/cancel lifecycle, adds `lake_tablet_stat_cancel_wait_ms`, and extends tests. | Avoids per-round executor churn and bounds cancel wait. Runtime replacement: `fe/lib/fe-core-4.1.1.jar`. |
| `6bf5e3f8e867d1d0dea2c8817726442c27644887` | Updates this change log for lake tablet stat executor reuse. | Docs only. |
| `00935906a808a5f80f973f1c919b43abc14749f5` | Records FE replacement artifact mapping. | Docs only. |
| `59c3b1750b3d9c51ddae263f2f2eaeefbabc7759` | Adds `MetaUtils.getRangeDistributionColumns(OlapTable, long)` compatibility and fixes Shield plugin jar naming/build compatibility. | FE build passes on current 4.1.1 APIs. Runtime replacement: `fe/lib/fe-core-4.1.1.jar` and `fe/lib/fe-plugin-shield-1.0.0.jar`. |
| `46af8f3929f2e1e870931b0fe920854729d2a9bb` | Adds BE lake tablet metadata read-back check, FE rollback shard deletion for cloud-native tables, focused FE/BE tests, and Shield plugin API imports for current 4.1.1. | Silent lake tablet metadata persistence failure becomes create-tablet failure; FE rollback deletes already-created StarOS shards. Runtime replacement: `be/lib/starrocks_be`, `fe/lib/fe-core-4.1.1.jar`, and `fe/lib/fe-plugin-shield-1.0.0.jar`. |

## Current Working Tree Update

2026-08-11 count fast path 物料构建与替换：

- 当前工作树的 FE/BE 修改已按源码边界映射为
  `fe/lib/fe-core-4.1.1.jar` 和 `be/lib/starrocks_be`；没有源码或 ABI 证据的
  `fe-spi`、Shield、Java extensions 和其他 `lib` 文件均未替换。
- 使用 `starrocks/dev-env-centos7:4.1-latest` 的长期容器
  `sr-dev-4.1.1-build` 构建。FE 使用 `fe-core` 定向 Maven reactor，BE 使用
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 28`，并确认
  `CMAKE_BUILD_TYPE=Release`、`USE_STAROS=ON`、`WITH_STARCACHE=ON`。
- 前置定向测试通过：FE `AggregateMetaTest#testAggregateCountMetaWithHasDeleteLakeTable`
  与 `TabletStatMgrTest#testUpdateLakeTabletStat`；BE `LakeServiceTest` 的 tablet stat、
  cache hit、delete predicate、PK approximate、PK accurate 五个用例。
- 物料树外备份目录为
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260811092726`。
  FE SHA-256 从
  `30865af16e1dd0dbb9905bfed02881a61b884804a392482e3b2332c6db067c5a` 更新为
  `516f472af26ad4ef6417ba4296ab669ce3e55b46e133a2ac54e2b659c1e92f7d`；BE SHA-256 从
  `d032ffef9e1e2da53b57ecd7c28d32411904cec86b3f61d90d0a22a6f8253ac3` 更新为
  `beb0d90fec1f05bd08a2f7c9732f70431b69f7a5f8670644ba4debde7a2668fc`。
- 替换后构建产物与物料目标通过 `cmp` 和 SHA-256 校验，目标 owner/group/mode 保持不变，
  FE 物料中没有 `fe-*-main.jar` 或 `spark-dpp-main.jar`。本次只更新本地 Docker 物料，
  未重建、推送或部署 Docker 镜像。

2026-07-09 `ebd-starrocks-crm-uat` 现场把 CN 高 CPU 与海量 `FileNotFoundException`
定位到"后台 tablet 统计回填扫描空初始分区"这条链路。现场证据：全库 `765577`
分区中 `648050` 个满足 `VISIBLE_VERSION=1 AND ROW_COUNT=0 AND DATA_SIZE=0`，CN 日志
5 分钟内绝大多数 `FileNotFound` 版本为 `0000000000000001`，路径首段为非零 tablet id
的 `.../meta/<tablet_id>_0000000000000001.meta`（legacy 探测），随后 initial 兜底成功
（FE `failed responses: 0`）。这说明 2026-07-08 的 bundle-first 修复没有覆盖
`version==kInitialVersion` 场景：`get_single_tablet_metadata` 对初始版本直接返回
`NotFound`，回退后仍先探测必然不存在的 legacy per-tablet 文件。

当前工作区两处修复（互补，均可运行时回滚）：

- FE `TabletStatMgr` 在采集前跳过仍处初始版本的物理分区
  （`visibleVersion <= PARTITION_INIT_VERSION`）。初始版本分区从未提交过导入，
  row_count/data_size 恒为 0，无需向 CN 发 `get_tablet_stats`。跳过判断为 O(1)
  版本比较，不发 RPC、不改 `dataSizeUpdateTime`，每轮重评估成本可忽略。语义与
  `ConsistencyChecker` 对初始版本"无数据"的既有判断一致。并行采集路径
  （`createCollectTabletStatJob`）与 CN-batch 路径（`collectStaleTabletsAndSubmitBatches`）
  两个调用点均生效。开关 `enable_lake_tablet_stat_skip_initial_version` 默认 `true`。
- CN `TabletManager::get_tablet_metadata(const string& path, ...)` 对
  `version==kInitialVersion && tablet_id!=0` 先读共享 initial metadata 文件
  （`0000000000000000_<kInitialVersion>.meta`），仅当其 `NotFound` 时才回退到
  legacy per-tablet 文件。对初始版本不再调用 `get_single_tablet_metadata`
  （它对 `kInitialVersion` 必然短路返回 `NotFound`，只会重复顶层已做过的一次
  metacache 查找），因此还顺带省掉一次冗余的 metacache 加锁查找。这样即使 FE
  漏跳（多 FE、升级、并发新建分区尚未 bump 版本），CN 也不再对空初始 tablet 制造
  一次可预期的对象存储 `FileNotFound`。非初始版本（`version>1`）路径逐字未变，
  查询热路径零影响。
  取舍（诚实标注）：`0000000000000000_<v=1>.meta` 是 file-bundling / 优化建表
  （`lake_enable_tablet_creation_optimization` 或 `table.isFileBundling()`，见
  `LocalMetastore.java:2051`）写入的共享 initial 文件；故障现场日志正是
  legacy-miss + initial-hit，证明该集群为此形态，"initial-first" 是纯收益。对
  未启用 file bundling 且关闭优化的旧部署（v=1 仅有 legacy 文件），会在初始版本
  读取上多一次 initial 文件 miss；但该路径在 FE 跳过后属低频（仅空分区查询/修复
  触发），可接受。

测试：FE `TabletStatMgrTest` 新增
`testParallelCollectionSkipsInitialVersionPartitions`、
`testCnBatchCollectionSkipsInitialVersionPartitions`、
`testInitialVersionSkipCanBeDisabled`；BE `tablet_manager_test` 新增
`get_tablet_metadata_initial_version_reads_initial_file_first`（用 SyncPoint 计数证明
`load_tablet_metadata` 从两次降到一次）与
`get_tablet_metadata_initial_version_falls_back_to_legacy_file`（旧格式 fallback）。
注意：受仓库规则限制，本次改动未在本地执行编译与单测，测试按既有模式补充，尚未实机运行。

2026-07-08 `starrocks-cluster-sync` 验证把新的瓶颈定位到 CN
`StarOSWorker` filesystem cache 构建路径：

- FE 日志显示 CN-batch lake tablet stat 收集已进入 `(cn-batch)` 路径，但进度
  停在 `81.82%` 超过 750 秒。
- CN 指标显示部分节点 `lake_metadata_fetch` 线程池打满，队列继续积压。
- CN `gdb` 栈显示多个 `lake_metadata_f` 线程阻塞在
  `pthread_rwlock_wrlock -> StarOSWorker::new_shared_filesystem ->
  StarletFileSystem::new_random_access_file -> ProtobufFile::load ->
  TabletManager::load_tablet_metadata`。
- `addr2line` 把阻塞点映射到 `be/src/service/staros_worker.cpp` 原
  `_cache_mtx` 全局写锁。
- 同时采到的高 CPU 线程是 `pip_poll_com`，栈在
  `PipelineDriverPoller::run_internal -> FragmentContext::need_report_exec_state`；
  它不是 metadata fetch 阻塞链路。

当前工作区把 `StarOSWorker::new_shared_filesystem` 从全局 filesystem cache
构建写锁改为按 cache key 的 singleflight map。同一个 cache key 仍只构建一次，
并用同一个结果唤醒等待线程；不同 cache key 不再被一把全局锁串行化。

2026-07-07 test-01 production validation found the CN-batch lake tablet stat
path unsafe without a per-CN hard limit:

- FE logs proved `lake_tablet_stat_batch_size=100` took effect
  (`tablets100>0`, `tablets512=0`), but no full CN-batch round completed.
- CN metrics showed one CN accumulating more than 3000 queued metadata tasks
  while FE still had many failed/timeout batches.
- Kubernetes pod state and events showed CN container restarts during the test
  window, including `exit=139` SIGSEGV and `BackOff` events.
- CN logs showed the crash happened during metadata open, with repeated
  `Fail to get tablet metadata` and Curvine client `master load job queue is full`
  messages.
- Curvine management checks remained fast (`cv report` completed in milliseconds),
  so the evidence points to StarRocks issuing an unsafe metadata-open workload,
  not to a generally slow Curvine cluster.
- Follow-up production evidence on 2026-07-08 showed the cold-round
  `FileNotFoundException` paths were `.../meta/<non-zero-tablet-id>_<version>.meta`,
  not bundle paths `.../meta/0000000000000000_<version>.meta`. The code root cause
  was `get_tablet_stats` entering `get_tablet_metadata(..., BundleMetadataCache*)`
  with an empty aggregation marker, then probing the legacy per-tablet metadata
  path before trying bundle metadata. The current worktree changes that
  bundle-aware path to try bundle metadata first, and falls back to legacy metadata
  only when the bundle path returns `NotFound`.

The current worktree therefore:

- changes `enable_lake_tablet_stat_cn_batch_collection` default from `true` to
  `false`;
- changes `lake_tablet_stat_batch_size` default from `512` to `100`;
- adds per-CN single-flight scheduling for CN-batch jobs in `TabletStatMgr`, so
  the same CN cannot receive a second batch while one is already in-flight;
- adds `TabletStatMgrTest.testCnBatchLimitsOneInFlightBatchPerComputeNode`;
- fixes `StarletFileSystem::drop_local_cache` so the default `size=-1` whole-file
  cache-clear path is converted to a concrete object length before calling
  Starlet, and a zero-length object returns without entering StarCache
  `drop_cache`. This prevents the Starlet block-range calculation from
  underflowing and looping over `0x00000fffffffffff` cache blocks.
- replaces `StarOSWorker` filesystem cache global build serialization with
  per-cache-key singleflight; `StarOSWorkerTest.test_fs_cache_build_waits_only_same_key`
  verifies same-key waiting and different-key non-blocking behavior.
- makes CN `get_tablet_stats` bundle-aware metadata lookup try bundle metadata
  before legacy `<tablet_id>_<version>.meta` probing, eliminating the cold
  non-zero metadata `FileNotFound` storm while retaining legacy fallback.

Runtime replacement: `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar` and
`StarRocks-4.1.1/be/lib/starrocks_be`.

Build/replacement evidence:

- FE build command used `starrocks/dev-env-centos7:4.1-latest` with
  `/home/oppo/.m2:/root/.m2` and `/mnt/data/maven-repo:/mnt/data/maven-repo`
  mounted.
- `./build.sh --fe -j 28` finished successfully at `2026-07-07 15:45:03`
  Asia/Shanghai.
- New FE jar SHA-256:
  `dc38ac2de04508a5cbb356897ade4940d4919e60ec2b88b079038c5cb1717ec1`.
- Docker material backup:
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260707154556`.
- BE build command used `starrocks/dev-env-centos7:4.1-latest` with
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8`; CMakeCache
  confirmed `USE_STAROS:BOOL=ON` and `WITH_STARCACHE:BOOL=ON`.
- New BE SHA-256:
  `3938af11fb6ca7e479908c1e47042367fc04ea46971acc616059cb19d90b104f`.
- Docker material BE backup:
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260707204304`.
- 2026-07-08 BE build command used `starrocks/dev-env-centos7:4.1-latest` with
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8`; CMakeCache
  confirmed `USE_STAROS:BOOL=ON` and `WITH_STARCACHE:BOOL=ON`.
- `StarOSWorkerTest.*` was run from the correct binary,
  `/mnt/data/starrocks/be/ut_build_ASAN/test/starrocks_test`, and passed 7/7 tests.
- New 2026-07-08 BE SHA-256:
  `f36e7c84b9c7e7c1205f6ccf4d7475cf53c61c656950a546750c58048e9bf181`.
- 2026-07-08 Docker material BE backup:
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260708124821`.
- 2026-07-08 focused Release regression tests after the bundle-first fix:
  `LakeTabletManagerTest.get_single_tablet_metadata_bundle_reuse`,
  `LakeTabletManagerTest.get_tablet_metadata_with_bundle_cache_skips_legacy_probe`,
  and `LakeTabletManagerTest.get_tablet_metadata_with_bundle_cache_falls_back_to_legacy_metadata`
  passed 3/3 in `starrocks/dev-env-centos7:4.1-latest`.
- 2026-07-08 later BE build command used the original Docker service, not a
  temporary data-root, with `starrocks/dev-env-centos7:4.1-latest` and
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 28`; the build
  printed `Successfully build StarRocks √ Backend` with `TotalTime:1457s`.
- New 2026-07-08 BE SHA-256 after the bundle-first fix:
  `4e4b84b33afef9a258d8dcec943f620d4588cdd73fbb2401d52b06d82bfcdda1`.
- 2026-07-08 Docker material BE backup after the bundle-first fix:
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260708164134`.
- Docker material replacement verification showed the material target
  `StarRocks-4.1.1/be/lib/starrocks_be` and the build artifact
  `/mnt/data/starrocks/output/be/lib/starrocks_be` have the same SHA-256:
  `4e4b84b33afef9a258d8dcec943f620d4588cdd73fbb2401d52b06d82bfcdda1`.
- 2026-07-10 current worktree rebuild found that the previously staged FE jar
  did not contain the new `TInternalScanRange.is_file_bundling` thrift field or
  the `OlapScanNode.setIs_file_bundling` call. FE was rebuilt with
  `starrocks/dev-env-centos7:4.1-latest` and
  `./build.sh --fe -j 28`; the build printed
  `Successfully build StarRocks √ Frontend` with `TotalTime:149s`.
- New 2026-07-10 FE SHA-256:
  `d18b435dd47ae112b9f4ea673aeac7fb6db465ad1964ba0afcb088cf17276302`.
  The rebuilt jar was verified by checking that
  `TInternalScanRange.class` contains `is_file_bundling` and
  `OlapScanNode.class` contains `setIs_file_bundling`.
- 2026-07-10 BE build used `starrocks/dev-env-centos7:4.1-latest` and
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8`; the build
  printed `Successfully build StarRocks √ Backend` with `TotalTime:5939s`.
  `CMakeCache.txt` confirmed `USE_STAROS:BOOL=ON` and `WITH_STARCACHE:BOOL=ON`.
- New 2026-07-10 BE SHA-256:
  `ff3a10d8272bc4167e43e87e52d1c6d8c7171da7e28eb0a01bee9f2dc7a0aff0`.
  Focused ASAN `LakeTabletManagerTest` regression coverage passed 7/7 tests for
  read-back check, bundle-first lookup, legacy fallback, prefer-bundle lookup,
  and concurrent bundle cache single-load.
- 2026-07-10 Docker material backups:
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260710011535/fe-lib`
  and
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260710003905/be-lib`.
- 2026-07-10 local image update did not use a full directory rebuild because
  `podman buildx build` hung at `COPY StarRocks-4.1.1 $STARROCKS_ROOT`; `strace`
  showed `podman` and both `buildah-copier` processes waiting in
  `futex(FUTEX_WAIT_PRIVATE)`. The final local image was produced by replacing
  only `fe/lib/fe-core-4.1.1.jar` and `be/lib/starrocks_be` in a temporary
  container and committing it. Final image ID:
  `174df1ec7696186c1e0b3abd0f96f485e32fdeefca088843d8ccfa4174e5626b`.
- 2026-07-10 later FE rebuild used the long-running
  `starrocks/dev-env-centos7:4.1-latest` container `sr-dev-4.1.1-build` and
  targeted only `fe-core`: `mvn --batch-mode -f fe/pom.xml -pl fe-core -am
  package -DskipTests -Dmaven.test.skip=true -Dmaven.clean.skip=true
  -Djacoco.skip=true -T 28`. `TabletStatMgrTest` passed 30/30 before packaging.
  The targeted package build finished in `31.924 s`.
- New 2026-07-10 targeted FE SHA-256:
  `adf2c0030a3e410e7f4a8a61ae7e4989c3b23890f07f21905cd95fd78b6bb572`.
  Docker material backup:
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260710154235/fe-lib`.
- 2026-07-10 later BE Release build used
  `starrocks/dev-env-centos7:4.1-latest` with
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8`; the build
  printed `Successfully build StarRocks √ Backend` with `TotalTime:6882s`.
  `CMakeCache.txt` confirmed `USE_STAROS:BOOL=ON` and `WITH_STARCACHE:BOOL=ON`.
- New 2026-07-10 later BE SHA-256:
  `17c16cdcd687366f0fd567bc68362a0ace1c0c5d850b3b0203c3c9c3fb2e960e`.
  Docker material backup:
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260710144356/be-lib`.

2026-08-08 current worktree validation and material replacement:

- FE `PublishVersionDaemonTest`, `StreamLoadMultiStmtTaskTest`, and
  `OlapTableSinkTest` passed 70/70. BE `LakeTabletManagerTest.*` passed 53/53,
  and `LakeServiceTest.*` passed 80/80. The BE test exercises the CN request
  slot: a second `get_tablet_stats` RPC waits until the active request releases.
- FE was packaged from `fe-core` with Python 3 in
  `starrocks/dev-env-centos7:4.1-latest`; its SHA-256 is
  `30865af16e1dd0dbb9905bfed02881a61b884804a392482e3b2332c6db067c5a`.
- BE was built in the same image using
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 28`; final output
  reported `Successfully build StarRocks Backend`, with SHA-256
  `d032ffef9e1e2da53b57ecd7c28d32411904cec86b3f61d90d0a22a6f8253ac3`.
  The final build, rather than its first link result, was copied to the material.
- Only `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar` and
  `StarRocks-4.1.1/be/lib/starrocks_be` were replaced. Backups are in
  `_backups_20260808013454`; byte-for-byte comparisons against the two final
  build artifacts passed.

2026-07-10 `80172b74f22` 补齐 file-bundling 表 `version>1` 场景的 FileNotFound 风暴：

- FE `OlapScanNode` 在 `TInternalScanRange` 写入 `is_file_bundling`（仅
  `isCloudNativeTableOrMaterializedView && isFileBundling()` 为 true 时设置）。
- CN `LakeDataSource::get_tablet` 读取该标记，配合
  `enable_lake_scan_prefer_bundle_metadata`（默认 `true`，`CONF_mBool`）走
  `get_tablet_metadata(..., prefer_bundle=true)`，在 aggregation marker 冷时
  仍 bundle-first，legacy per-tablet 路径仅作 `NotFound` fallback。
- CN `put_bundle_tablet_metadata` 增加
  `lake_aggregate_publish_readback_check`（默认 `true`，`CONF_mBool`）读回校验，
  将 bundle 未持久化从静默成功改为 publish 失败。
- 与 2026-07-09 初始版本修复互补：v=1 仍走 initial-first；stat 路径仍靠
  `bundle_cache`；scan 路径靠 FE 透传 + `prefer_bundle`。
- 测试：`LakeTabletManagerTest` 7/7 通过（含 `get_tablet_metadata_prefer_bundle_skips_legacy_probe`
  与 read-back 校验用例）。

## Custom Parameter Index

Use this index to find fork-added knobs quickly. Values are defaults in the
current `branch-4.1.1` source.

### FE Global Config

| Parameter | Default | Added by | Source | Purpose |
| --- | --- | --- | --- | --- |
| `catalog_recycle_bin_erase_max_pending_partition_delete_tasks` | `0` | `b498560f5d0...` | `fe/fe-core/src/main/java/com/starrocks/common/Config.java` | Limits pending async partition delete tasks; `0` or negative means unlimited. |
| `catalog_recycle_bin_erase_max_new_partition_delete_tasks_per_cycle` | `0` | `b498560f5d0...` | `Config.java` | Limits new async partition delete tasks submitted per recycle-bin erase cycle; `0` or negative means unlimited. |
| `star_mgr_meta_sync_max_delete_shards_per_round` | `0` | `b498560f5d0...` | `Config.java` | Limits shard deletions per StarMgrMetaSyncer cleanup round; `0` or negative means unlimited. |
| `star_mgr_meta_sync_max_clean_groups_per_round` | `0` | `b498560f5d0...` | `Config.java` | Limits shard groups processed per StarMgrMetaSyncer cleanup round; `0` or negative means unlimited. |
| `star_mgr_meta_sync_max_runtime_ms_per_round` | `0` | `b498560f5d0...` | `Config.java` | Limits StarMgrMetaSyncer cleanup runtime per round; `0` or negative means unlimited. |
| `star_mgr_meta_sync_abort_current_round` | `false` | `b498560f5d0...` | `Config.java` | Stops or skips the current StarMgrMetaSyncer shard cleanup round. |
| `enable_parallel_lake_tablet_stat_collection` | `false` | `bf21f562e91...` | `Config.java` | Enables parallel shared-data tablet stat collection. |
| `lake_tablet_stat_collect_parallelism` | `16` | `bf21f562e91...` | `Config.java` | Worker count for parallel lake tablet stat collection. |
| `lake_tablet_stat_max_inflight_tasks` | `256` | `bf21f562e91...` | `Config.java` | Max unfinished lake tablet stat jobs globally; CN-batch additionally limits one in-flight batch per CN. |
| `lake_tablet_stat_collect_slow_log_ms` | `5000` | `bf21f562e91...` | `Config.java` | Slow-log threshold for one partition-level stat job. |
| `lake_tablet_stat_cancel_wait_ms` | `5000` | `b00d41c9e32...` | `Config.java` | Max wait for canceled lake tablet stat jobs before executor recreation. |
| `enable_lake_tablet_stat_cn_batch_collection` | `false` | current worktree | `Config.java` | Enables CN-batch lake tablet stat collection; default is conservative rollback to the legacy path. |
| `lake_tablet_stat_batch_size` | `100` | current worktree | `Config.java` | Max tablets per CN-batch `get_tablet_stats` request. |
| `enable_lake_tablet_stat_skip_initial_version` | `true` | current worktree | `Config.java` | Skips stat collection for physical partitions still at the initial version (`visibleVersion <= PARTITION_INIT_VERSION`); such partitions are guaranteed empty, so no `get_tablet_stats` RPC is issued. |

### BE Config

| Parameter | Default | Added by | Source | Purpose |
| --- | --- | --- | --- | --- |
| `lake_create_tablet_readback_check` | `true` | `46af8f3929f...` | `be/src/common/config.h` | Reads just-written lake initial tablet metadata back from remote storage before `create_tablet` returns success. |
| `enable_lake_scan_prefer_bundle_metadata` | `true` | `80172b74f22` | `be/src/common/config.h` | For FE-marked file-bundling lake scans, read shared bundle tablet metadata before probing the legacy per-tablet metadata path; legacy metadata remains the `NotFound` fallback. `CONF_mBool`, runtime mutable. |
| `lake_aggregate_publish_readback_check` | `true` | `80172b74f22` | `be/src/common/config.h` | After aggregate/file-bundling publish writes bundle tablet metadata, read it back from remote storage before reporting publish success. `CONF_mBool`, runtime mutable. |

### Shield Catalog Properties

These properties are read by `fe-plugin-shield/src/main/java/com/oppo/starrocks/shield/ShieldConfig.java`.

| Property | Default | Required | Purpose |
| --- | --- | --- | --- |
| `shield.api.domain` | none | yes | Shield API domain. |
| `shield.api.app_key` | none | yes | Shield API app key. |
| `shield.api.operator` | empty string | no | Shield API operator. |
| `shield.api.sys_id` | `starrocks` | no | Shield system id. |
| `shield.api.area_code` | `china1` | no | Shield area code. |
| `shield.api.user_app_group_path` | `/oauthority/api/getUserAppGroup` | no | API path for user group lookup. |
| `shield.api.group_permissions_path` | `/oauthority/api/getResourcesByGroupID` | no | API path for group permissions. |
| `shield.super_admin_users` | empty set | no | Comma-separated users that bypass Shield checks. |
| `shield.permission.cache.ttl.seconds` | `60` | no | Shield permission cache TTL. |
| `shield.rpd.area_filter` | `<area_code>/` | no | RPD area prefix filter. |
| `shield.api.slow.threshold.ms` | `500` | no | Shield API slow-call log threshold. |

After connecting, clients can run `SET shield_app_group = 'bdp'`. If the user's direct permission does not
match, Shield verifies that the user belongs to `bdp` under the login PSA and checks only that group's
permissions. If the variable is empty, only the user's direct permissions are checked.

### Shield Shared-Password Security Integration Properties

These properties are read by `ShieldSharedAuthConfig` and
`PluginSecurityIntegrationSupport`.

| Property | Default | Required | Purpose |
| --- | --- | --- | --- |
| `type` | none | yes | Must be `authentication_shield_shared`. |
| `shield.shared.password` | none | one of password/password_hash/password_file | Write-only plain password; converted to `shield.shared.password_hash` before persistence. |
| `shield.shared.password_hash` | none | one of password/password_hash/password_file | Persisted MySQL scrambled password. |
| `shield.shared.password_file` | none | one of password/password_hash/password_file | Legacy local file password source. |
| `shield.shared.username_pattern` | `^\\d+_\\d+$` | no | Regex for accepted ephemeral Shield usernames. |
| `shield.shared.verify_shield_on_login` | `false` | no | Calls Shield during login when true. |

## Replacement Artifacts

Record the runtime file to replace for fork-owned code commits when they are
added or ported. The table below records replacement artifacts verified in this
document update; do not infer replacement files for older commits that are not
listed here. Use `N/A` only for docs-only commits.
For the current 4.1.1 package, the verified root is
`/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1`.

| Commit | Runtime file to replace | Evidence |
| --- | --- | --- |
| `bf21f562e914918721285418bd4ccb526c5fda4f` | `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar` | Adds `TabletStatMgr` and `Config` lake tablet stat logic. |
| `6fcba194b3ef1ace8ed024dc6d94359decca4da3` | `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar` | Changes `com/starrocks/catalog/TabletStatMgr.class`. |
| `b00d41c9e32dd4525d80bf45a543cf9e2822c1e9` | `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar` | `jar tf .../fe/lib/fe-core-4.1.1.jar` contains `com/starrocks/catalog/TabletStatMgr.class` and `com/starrocks/common/Config.class`. |
| `3439a375bd26a96691603cfdec0ee6cc3c8dda63` | `StarRocks-4.1.1/be/lib/starrocks_be` | Changes BE lake vacuum code compiled into the BE binary. |
| `59c3b1750b3d9c51ddae263f2f2eaeefbabc7759` | `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar`, `StarRocks-4.1.1/fe/lib/fe-plugin-shield-1.0.0.jar` | Changes `MetaUtils` in FE core and Shield plugin packaging compatibility. |
| `46af8f3929f2e1e870931b0fe920854729d2a9bb` | `StarRocks-4.1.1/be/lib/starrocks_be`, `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar`, `StarRocks-4.1.1/fe/lib/fe-plugin-shield-1.0.0.jar` | Changes BE lake tablet manager/config, FE `LocalMetastore`, and Shield plugin classes. |
| Current working tree | `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar`, `StarRocks-4.1.1/be/lib/starrocks_be` | Changes `TabletStatMgr` CN-batch scheduling and initial-version skip, plus `Config` CN-batch/skip defaults; changes `StarletFileSystem::drop_local_cache` cache-clear length handling; changes `StarOSWorker::new_shared_filesystem` to per-cache-key singleflight; changes lake `TabletManager` metadata lookup to try bundle metadata before legacy per-tablet probing and to read the shared initial metadata file first for the initial version. |
| Docs-only commits | `N/A` | No runtime replacement. |

## 2026-07-04 4.1.1 Package Replacement Whitelist

The verified 4.1.1 Docker material root is:
`/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1`.

Only the following runtime files were replaced in that material tree. Do not
replace the whole `fe/lib` or `be/lib` directory for this change set.

| Runtime file | SHA-256 after replacement | Evidence |
| --- | --- | --- |
| `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar` | `5c6a856c784a06ac959f7f1710a15e87c579589ca92f2e7bd8f16443ead15626` | Contains FE core changes, including `LocalMetastore` and `MetaUtils`. |
| `StarRocks-4.1.1/fe/lib/fe-spi-4.1.1.jar` | `588c33e9e5e6c7d18871574d862d3f5d885eb8f48aad5fdea9fa6cf723a29afc` | Built and deployed with the FE 4.1.1 API alignment set. |
| `StarRocks-4.1.1/fe/lib/fe-plugin-shield-1.0.0.jar` | `e45de6de027a80376da382aa7e9766a917b9845b5c18f5f5b31e818d1d5ef107` | Contains Shield plugin API compatibility changes. |
| `StarRocks-4.1.1/be/lib/starrocks_be` | `659178609fddcdb0d56bc719dd44472ec12cdaab3f59521c341d6c555e08af3b` | Contains BE lake vacuum and lake tablet creation changes. |

Replacement-scope evidence from the material tree:

```text
2026-07-03 23:09:06.3113190000 26055 StarRocks-4.1.1/fe/lib/fe-spi-4.1.1.jar
2026-07-03 23:22:31.7856860000 29336870 StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar
2026-07-03 23:23:13.9461587970 40764 StarRocks-4.1.1/fe/lib/fe-plugin-shield-1.0.0.jar
2026-07-04 04:47:54.5968276730 527510112 StarRocks-4.1.1/be/lib/starrocks_be
```

Negative evidence against full-library replacement:

- `StarRocks-4.1.1/fe/lib/netty-common-4.1.135.Final.jar` is absent.
- `StarRocks-4.1.1/fe/lib/netty-common-4.1.133.Final.jar` is present.
- No `*.bak-*`, `lib.bak*`, or `lib.changed-backup*` files remain inside
  `StarRocks-4.1.1`; backups were moved outside the material tree.

Build evidence:

- FE: built in `starrocks/dev-env-centos7:4.1-latest`; targeted FE tests passed
  before replacement.
- BE: built in `starrocks/dev-env-centos7:4.1-latest` with
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8`.
- The requested `-j 28` build was stopped after direct evidence showed the
  bottleneck was disk I/O, not CPU: many compiler processes were in uninterruptible
  I/O wait and CPU utilization was low. Restarting with `-j 8` completed the BE
  build and avoided further I/O saturation.

## Compatibility Notes

- Shared-nothing mode (存算一体): `updateLocalTabletStat()` still returns unless
  `RunMode.isSharedNothingMode()` is true. This path does not create the lake
  tablet stat executor.
- Shared-data mode (存算分离): `updateLakeTabletStat()` still returns unless
  `RunMode.isSharedDataMode()` is true. The lake tablet stat executor is lazy
  and is used only by the parallel partition path or by CN-batch when those paths
  are explicitly enabled.
- Default behavior remains the legacy serial path because both
  `enable_lake_tablet_stat_cn_batch_collection` and
  `enable_parallel_lake_tablet_stat_collection` default to false.

## OPPO Ports On branch-4.1.2

The local 4.1.2 fork exists at `/mnt/data/starrocks-4.1.2-fork`.
The following commits were observed there on `branch-4.1.2`.

| Commit | Author | Area | Purpose |
| --- | --- | --- | --- |
| `55ff32e09b592bfd86e3e501af0bb48d103dd6b7` | `jianglong@oppo.com` | BE cache | Avoid filling data cache from metadata/stat RPCs. |
| `4c3eec8c3a3ac184b6dd3a50bc3d94624ebdc6c3` | `jianglong@oppo.com` | FE shared-data cleanup | Protect StarMgr metadata cleanup in shared-data mode. |
| `1c2a823de17f0510a6294450d725bd1790bdddc6` | `qiunan1@oppo.com` | Shield plugin | Enforce Shield permissions at query time through catalog properties. |
| `164f4d7460dfab70c0abb34137a291638700e34d` | `qiunan1@oppo.com` | Deploy, FE/CN image, Jindo, Shield | Port unified image pipeline, Jindo support, CN entrypoint fix, libthrift update, and Shield/Jindo staging. |
| `65516092be294b1cb9bc03af72e6e413409afc3a` | `qiunan1@oppo.com` | Shield auth | Enable `authentication_shield_shared` shared-password login. |
| `1a9fdf8a236d06a3ccb331e98715fd5d13b492fe` | `qiunan1@oppo.com` | FE entrypoint, Shield auth | Expand `meta_dir` with `POD_NAME` and rebuild Shield shared-auth config after restart. |
| `1c887a3bef610d23575e852b15c07ac39e1db1b2` | `jianglong@oppo.com` | BE lake vacuum | Avoid aborting on malformed txn log filenames. |
| `c76da8f080cab3061d49e916ff71ffd392c3bc91` | `jianglong@oppo.com` | 4.1.2 port | Align fork-only changes with StarRocks 4.1.2 APIs and build files. |

## Document Maintenance Note

The audited range ends at `46af8f3929f2e1e870931b0fe920854729d2a9bb`.
The commit that updates this document after that code commit is not self-listed,
because a Git commit cannot contain its own final hash. Record that document
maintenance commit in the next audit if it needs to be tracked explicitly.

## Current Unclassified Local Files

Uncommitted local files are not classified as OPPO-owned changes in this
document until they are reviewed and committed. Use `git status --short` for
the current working tree state.

## Update Rules

1. Add every OPPO-owned commit with full hash, author, area, and purpose.
2. Keep upstream StarRocks backports separate from fork-owned changes.
3. Record the runtime file to replace for every code commit, with class or
   binary evidence.
4. Move pending work into a commit table only after the commit exists.
5. When porting to a new branch, record the new commit and source change.
