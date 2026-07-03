# StarRocks 4.1.1 编译与 Docker 物料替换指南

日期：2026-07-04
工作区：`/mnt/data/starrocks`
目标分支：`branch-4.1.1`
目标物料根目录：
`/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1`

本文记录 2026-07-03 到 2026-07-04 的实际编译、测试、替换过程。后续处理
4.1.1 分支时按本文执行，除非源码或物料目录已经变化。

## 原则

1. 使用 `starrocks/dev-env-centos7:4.1-latest` 编译 FE 和 BE。
2. 先跑对应模块测试，再编译，再替换物料。
3. 只替换有源码变更证据对应的运行产物，不复制整个 `fe/lib` 或 `be/lib`。
4. 替换前后记录 `sha256sum`、mtime、文件大小。
5. 备份放在物料树外，避免被 Docker build 复制进镜像。
6. 发现 CPU 空闲但进程处于 I/O wait 时，降低并发，不继续提高 `-j`。
7. FE 物料禁止包含 `fe-*-main.jar` 或 `spark-dpp-main.jar`。2026-07-06 已证明旧 `fe-parser-main.jar` 会让 JVM 先加载错误的 `ShowStmt.class`，触发 `NoSuchMethodError`。

## 必备环境

所有命令从 `/mnt/data/starrocks` 执行。当前环境要求命令经 `rtk` 包装。

```bash
rtk docker image inspect starrocks/dev-env-centos7:4.1-latest >/dev/null
rtk git status --short --branch
```

本机 Maven 配置目录是 `/home/oppo/.m2`，其中 `settings.xml` 指定
`localRepository=/mnt/data/maven-repo`。dev-env 容器内 Maven 以 root 用户运行，
默认读取 `/root/.m2/settings.xml`，并按 settings 里的绝对路径读取
`/mnt/data/maven-repo`。因此所有手工 `docker run` 编译/测试命令都必须挂载：

```bash
-v /home/oppo/.m2:/root/.m2
-v /mnt/data/maven-repo:/mnt/data/maven-repo
```

不要把 host 的 `.m2` 挂到容器内 `/home/oppo/.m2`。这个路径不会被 root 用户下
的 Maven 默认读取。也不要只挂 `/home/oppo/.m2:/root/.m2` 而漏掉
`/mnt/data/maven-repo:/mnt/data/maven-repo`，否则 settings 会指向容器内不存在
或空的 localRepository，FE 编译会退化成冷缓存下载。

本机兼容链接：

```bash
/home/oppo/.m2/repository -> /mnt/data/maven-repo
```

验证命令：

```bash
mvn -q -DforceStdout help:evaluate -Dexpression=settings.localRepository
readlink /home/oppo/.m2/repository
```

如果工作区有未提交改动，先分类：

- 属于本次功能的源码和测试，纳入测试、编译和提交。
- 无关未跟踪文件，不暂存、不删除。
- 不使用 `git reset --hard` 或 `git checkout --` 清理用户改动。

## 产物映射规则

按源码变更决定运行产物：

| 源码变更区域 | 运行产物 |
| --- | --- |
| `fe/fe-core/**` | `StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar` |
| `fe/fe-spi/**` | `StarRocks-4.1.1/fe/lib/fe-spi-4.1.1.jar` |
| `fe/fe-plugin-shield/**` | `StarRocks-4.1.1/fe/lib/fe-plugin-shield-1.0.0.jar` |
| `be/src/**` | `StarRocks-4.1.1/be/lib/starrocks_be` |
| `java-extensions/**` | 对应 extension jar；没有源码变更就不替换 |
| `deploy/**`, `docker/**`, `bin/**` | 镜像构建脚本或入口文件；按文件路径替换或重新构建镜像 |

当前已替换的运行产物白名单：

| 文件 | SHA-256 |
| --- | --- |
| `fe/lib/fe-core-4.1.1.jar` | `dc38ac2de04508a5cbb356897ade4940d4919e60ec2b88b079038c5cb1717ec1` |
| `fe/lib/fe-spi-4.1.1.jar` | `588c33e9e5e6c7d18871574d862d3f5d885eb8f48aad5fdea9fa6cf723a29afc` |
| `fe/lib/fe-plugin-shield-1.0.0.jar` | `e45de6de027a80376da382aa7e9766a917b9845b5c18f5f5b31e818d1d5ef107` |
| `be/lib/starrocks_be` | `4e4b84b33afef9a258d8dcec943f620d4588cdd73fbb2401d52b06d82bfcdda1` |

2026-07-08 12:48 更新记录：

- 按源码 diff 映射，只替换 `be/lib/starrocks_be`。
- `starrocks-cluster-sync` 验证中，FE 已进入 CN-batch 路径，但一轮 lake
  tablet stat 收集停在 `81.82%` 超过 750 秒；CN 指标显示部分节点
  `lake_metadata_fetch` 线程池打满并积压队列。
- CN `gdb` 栈显示多个 `lake_metadata_f` 线程阻塞在
  `pthread_rwlock_wrlock -> StarOSWorker::new_shared_filesystem ->
  StarletFileSystem::new_random_access_file -> ProtobufFile::load ->
  TabletManager::load_tablet_metadata`；`addr2line` 落到
  `be/src/service/staros_worker.cpp` 原全局 `_cache_mtx` 写锁。
- BE/CN 改动集中在 `be/src/service/staros_worker.*`：把 filesystem cache
  构建从全局写锁改为按 cache key 的 singleflight。同 key 仍等待并复用一次
  构建结果；不同 key 不再互相串行。
- 新增 `StarOSWorkerTest.test_fs_cache_build_waits_only_same_key`，验证同 key
  等待、不同 key 不等待。
- 测试命令：
  `starrocks_test --gtest_filter="StarOSWorkerTest.*"`。
- 测试结果：
  `[==========] 7 tests from 1 test suite ran.` 和 `[  PASSED  ] 7 tests.`。
- 新 BE 二进制来自 `/mnt/data/starrocks/output/be/lib/starrocks_be`。
- 编译命令使用 `starrocks/dev-env-centos7:4.1-latest`：
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8`。
- 编译配置确认：
  `USE_STAROS:BOOL=ON`，`WITH_STARCACHE:BOOL=ON`。
- 编译结果：
  `Successfully build StarRocks √ Backend`，`TotalTime:568s`。
- 备份目录：
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260708124821`。
- 旧 BE SHA-256：
  `3938af11fb6ca7e479908c1e47042367fc04ea46971acc616059cb19d90b104f`。
- 新 BE SHA-256：
  `f36e7c84b9c7e7c1205f6ccf4d7475cf53c61c656950a546750c58048e9bf181`。
- 替换后验证：构建产物和物料目标 SHA 一致；`2026-07-08 12:48:00`

2026-07-08 15:19 更新记录：

- `starrocks-cluster-sync` 后续验证定位到冷 metacache 下的非零 tablet
  metadata 失败打开风暴：CN 日志中的 `FileNotFoundException` 路径为
  `.../meta/<tablet_id>_<version>.meta`，不是 bundle 路径
  `.../meta/0000000000000000_<version>.meta`。
- 修复集中在 `be/src/storage/lake/tablet_manager.cpp`：`get_tablet_stats`
  传入 request-local `BundleMetadataCache` 时先读 bundle metadata；只有 bundle
  路径返回 `NotFound` 才 fallback 到旧格式 per-tablet metadata。非
  `NotFound` 错误直接返回，不吞掉真实 I/O、解析或损坏错误。
- 运行产物映射仍只涉及 `be/lib/starrocks_be`。
- 已用 `starrocks/dev-env-centos7:4.1-latest` 重新构建
  `be/build_Release_ut/test/storage/lake/tablet_manager_test`。最终链接会占用
  20GB 以上内存并大量 I/O wait；这是当前构建环境的事实，不要误判为测试卡死。
- Focused Release 回归测试通过 3/3：
  `get_single_tablet_metadata_bundle_reuse`、
  `get_tablet_metadata_with_bundle_cache_skips_legacy_probe`、
  `get_tablet_metadata_with_bundle_cache_falls_back_to_legacy_metadata`。
之后物料树内只有 `StarRocks-4.1.1/be/lib/starrocks_be` 被更新；物料树内
  `_backups_*` 扫描为空；FE 旧 `*-main.jar` 扫描为空。

2026-07-08 16:41 更新记录：

- 按源码 diff 映射，只替换 `be/lib/starrocks_be`。
- 本次 BE 改动仍集中在 `be/src/storage/lake/tablet_manager.cpp` 的
  bundle-aware metadata lookup：CN `get_tablet_stats` 传入
  `BundleMetadataCache` 时优先读取 bundle metadata，只有 bundle 路径返回
  `NotFound` 才 fallback 到旧格式 per-tablet metadata。
- 已在原始 Docker 服务上编译，不使用临时 Docker data-root。Docker 服务状态证据：
  `server=28.3.3 driver=overlay2 root=/mnt/data/docker-data`，并且
  `docker run --rm starrocks/dev-env-centos7:4.1-latest ...` 可正常启动。
- 编译命令使用 `starrocks/dev-env-centos7:4.1-latest`：
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 28`。
- 编译配置确认：
  `WITH_STARCACHE ON`，`ENABLE_SHARED_DATA ON`，编译 flags 包含
  `-DUSE_STAROS -DWITH_STARCACHE`。
- 编译结果：
  `Successfully build StarRocks √ Backend`，`StartTime:2026-07-08 08:07:58,
  EndTime:2026-07-08 08:32:15, TotalTime:1457s`。
- `build.sh --be` 同步生成未拆分调试符号的
  `/mnt/data/starrocks/be/output/lib/starrocks_be` 和最终运行二进制
  `/mnt/data/starrocks/output/be/lib/starrocks_be`；物料替换使用后者。
- 新 BE 二进制 SHA-256：
  `4e4b84b33afef9a258d8dcec943f620d4588cdd73fbb2401d52b06d82bfcdda1`。
- 替换前物料 BE SHA-256：
  `f36e7c84b9c7e7c1205f6ccf4d7475cf53c61c656950a546750c58048e9bf181`。
- 备份目录：
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260708164134`。
- 替换后验证：物料目标
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/be/lib/starrocks_be`
  与构建产物 SHA 一致，均为
  `4e4b84b33afef9a258d8dcec943f620d4588cdd73fbb2401d52b06d82bfcdda1`。

2026-07-07 更新记录：

- 15:45 按源码 diff 映射，只替换 `fe/lib/fe-core-4.1.1.jar`。
- 本次 FE 改动集中在 `fe/fe-core/**`：`TabletStatMgr` 同 CN 单飞调度、CN-batch 默认关闭、batch size 默认 `100` 和相关测试。
- 新 FE jar 来自 `/mnt/data/starrocks/output/fe/lib/fe-core-4.1.1.jar`。
- 备份目录：
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260707154556`。
- 旧 FE jar SHA-256：
  `2f87edf6dbb413085c678d9bfd053e996becfa3c9332cdb274a3b137149755ab`。
- 新 FE jar SHA-256：
  `dc38ac2de04508a5cbb356897ade4940d4919e60ec2b88b079038c5cb1717ec1`。
- BE/CN 本轮没有源码变更后的重新编译产物，不替换 `be/lib/starrocks_be`。

2026-07-07 20:43 更新记录：

- 按源码 diff 映射，只替换 `be/lib/starrocks_be`。
- BE/CN 改动集中在 `be/src/**`：`StarletFileSystem::drop_local_cache`
  将默认 `size=-1` 归一化为真实对象长度，并对 `size=0` 直接返回，避免
  Starlet/StarCache `drop_cache` 对零长度对象计算 block 范围时发生下溢。
- 新 BE 二进制来自 `/mnt/data/starrocks/output/be/lib/starrocks_be`。
- 编译命令使用 `starrocks/dev-env-centos7:4.1-latest`：
  `BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8`。
- 编译配置确认：
  `USE_STAROS:BOOL=ON`，`WITH_STARCACHE:BOOL=ON`。
- 备份目录：
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260707204304`。
- 旧 BE SHA-256：
  `c3af33dc41cb09471a8271d21de0909d60c1ee3ca9ad90d02a81786056f2bd1b`。
- 新 BE SHA-256：
  `3938af11fb6ca7e479908c1e47042367fc04ea46971acc616059cb19d90b104f`。
- 替换后验证：`sha256sum` 显示构建产物和物料目标一致；物料树内备份污染扫描为空；
  FE 旧 `*-main.jar` 扫描为空；`2026-07-07 20:35:00` 之后仅
  `StarRocks-4.1.1/be/lib/starrocks_be` 被更新。

2026-07-07 06:52 更新记录：

- 按源码 diff 映射，只替换 `fe/lib/fe-core-4.1.1.jar` 和 `be/lib/starrocks_be`。
- FE 改动集中在 `fe/fe-core/**`：`TabletStatMgr` CN batch 默认调度、相关 FE 参数注释和 `TabletStatMgrTest`。
- BE/CN 改动集中在 `be/src/**`：`get_tablet_stats` request-local bundle 复用、独立 `LakeTabletStatCache` 和相关 lake service/tablet manager 逻辑。
- 未替换 `fe-spi-4.1.1.jar`、`fe-plugin-shield-1.0.0.jar` 或其它 FE `lib` 文件。
- 新 FE jar 来自 `/mnt/data/starrocks/output/fe/lib/fe-core-4.1.1.jar`。
- 新 BE 二进制来自 `/mnt/data/starrocks/output/be/lib/starrocks_be`。
- 备份目录：
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260707065220`。
- 旧 FE jar SHA-256：
  `44bc66f926baede8a70c69adb6c33e27421af60d20cfc17d7ca8e6a31850bb29`。
- 新 FE jar SHA-256：
  `2f87edf6dbb413085c678d9bfd053e996becfa3c9332cdb274a3b137149755ab`。
- 旧 BE SHA-256：
  `659178609fddcdb0d56bc719dd44472ec12cdaab3f59521c341d6c555e08af3b`。
- 新 BE SHA-256：
  `c3af33dc41cb09471a8271d21de0909d60c1ee3ca9ad90d02a81786056f2bd1b`。

2026-07-06 更新记录：

- 只替换 `fe/lib/fe-core-4.1.1.jar`。
- 新 jar 来自 `/mnt/data/starrocks/output/fe/lib/fe-core-4.1.1.jar`。
- 备份目录：
  `/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_20260706132734`。
- 旧 jar SHA-256：
  `5c6a856c784a06ac959f7f1710a15e87c579589ca92f2e7bd8f16443ead15626`。
- 新 jar SHA-256：
  `44bc66f926baede8a70c69adb6c33e27421af60d20cfc17d7ca8e6a31850bb29`。

运行时闭包检查：

```bash
rtk bash -lc 'base=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1; \
find "$base/fe/lib" -maxdepth 1 \( -name "fe-*-main.jar" -o -name "spark-dpp-main.jar" \) | wc -l; \
find "$base/fe/lib" -maxdepth 1 -name "libthrift-0.20.0.jar" | wc -l; \
find "$base/fe/lib" -maxdepth 1 -name "netty-*4.1.133.Final*.jar" | wc -l; \
find "$base/be/lib" -maxdepth 3 -name "libthrift-0.20.0.jar" | wc -l; \
find "$base/be/lib" -maxdepth 3 -name "netty-*4.1.133.Final*.jar" | wc -l'
```

期望结果：

```text
0
0
0
0
0
```

这证明旧 `*-main.jar`、旧 thrift、旧 Netty 没有进入 4.1.1 物料。

## 自定义参数

日常使用默认走旧的稳定采集路径。灰度验证 CN-batch 时只需要关注两个 FE 参数：

| 参数 | 默认值 | 作用 | 验证方式 |
| --- | --- | --- | --- |
| `enable_lake_tablet_stat_cn_batch_collection` | `false` | 打开后 FE 按 CN batch 发送 stale lake tablets；关闭时回到旧采集路径。当前默认关闭，因为 test-01 生产验证证明无 per-CN 限流的 CN-batch 会触发 CN 队列堆积和进程重启。 | FE summary 名称包含 `(cn-batch)`，CN 收到的 `get_tablet_stats` 请求包含多个 tablet。 |
| `lake_tablet_stat_batch_size` | `100` | FE CN-batch 单个请求的 tablet 上限；同一 partition 的 tablets 会尽量保持在同一 batch，只有单个 partition 超过该值才拆分。CN-batch 额外按 CN 做单飞调度，同一 CN 同时最多一个 batch in-flight。 | 抓 FE 测试/日志或 CN RPC 观测，单个请求 tablet 数不超过该值；`TabletStatMgrTest.testCnBatchLimitsOneInFlightBatchPerComputeNode` 验证同 CN 不并发。 |

其余 FE 参数属于高级调优或诊断参数。普通使用人员不需要调整。

| 参数 | 默认值 | 作用 | 验证方式 |
| --- | --- | --- | --- |
| `enable_parallel_lake_tablet_stat_collection` | `false` | 旧 partition-level 并行采集回退路径；仅当 `enable_lake_tablet_stat_cn_batch_collection=false` 时生效。 | FE summary 日志出现 `finished to collect lake tablet stat for ... in parallel` 且名称不包含 `(cn-batch)`。 |
| `lake_tablet_stat_collect_parallelism` | `16` | FE lake tablet stat collector 线程池并行度。 | FE progress/summary 日志里的 `parallelism` 等于配置值。 |
| `lake_tablet_stat_max_inflight_tasks` | `256` | FE lake tablet stat collector 全局 in-flight 上限。 | FE progress/summary 日志里的 `max in-flight config` 等于配置值，`max in-flight partitions` 不超过该值。 |
| `lake_tablet_stat_progress_log_interval_ms` | `-1` | 控制 TabletStatMgr lake tablet stat 并行采集进度日志间隔。正数表示按该毫秒间隔打印进度；非正数表示关闭。 | FE 日志出现 `lake tablet stat collection progress for ...: progress: ... seen partitions, in-flight: ..., submitted: ..., completed: ...`。 |
| `lake_tablet_stat_collect_slow_log_ms` | `5000` | FE 侧慢 partition/batch 日志阈值，单位 ms。 | FE 日志出现 `slow lake tablet stat collection` 或 `slow lake tablet stat batch`。 |
| `lake_tablet_stat_cancel_wait_ms` | `5000` | FE 停止 collector 时等待已取消任务退出的时间，单位 ms。 | 停止/重启 FE 时无长时间残留的 lake tablet stat collection job。 |

CN 侧日常使用只需要知道一个回滚开关。默认值已经启用 request-local bundle 复用和独立 stat cache，普通使用人员不需要配置容量、TTL 或清理周期。

| 参数 | 默认值 | 作用 | 验证方式 |
| --- | --- | --- | --- |
| `enable_lake_tablet_stat_cache` | `true` | BE/CN 默认启用独立 tablet stat cache；发现 cache 相关问题时关闭即可回退。request-local bundle 复用没有外部参数，随 `get_tablet_stats` 自动生效。 | 重复 `get_tablet_stats` 请求时 cache hit 增加；关闭后 hit/miss 计数不变。 |

其余 CN 参数属于证据驱动的高级诊断或容量调优参数。只有看到对应指标或日志证据时才调整。

| 参数 | 默认值 | 作用 | 验证方式 |
| --- | --- | --- | --- |
| `lake_enable_accurate_pk_row_count` | `true` | BE/CN 是否为 PK tablet 读取 delete vector 以计算准确 row count。关闭后使用近似值，减少 metadata I/O。 | BE/CN 配置生效后，PK tablet `get_tablet_stats` 不再进入 accurate delete vector 读取路径。 |
| `lake_tablet_stat_slow_log_ms` | `300000` | BE/CN 单 tablet stat 采集慢日志阈值，单位 ms。 | BE/CN 日志出现 `slow get tablet stat` 相关记录。 |
| `lake_tablet_stat_cache_capacity` | `1048576` | BE/CN tablet stat cache 条目上限；`0` 表示关闭。 | BE/CN 指标或日志中的 stat cache hit/miss 变化；缓存条目数不超过该值。 |
| `lake_tablet_stat_cache_ttl_sec` | `3600` | BE/CN tablet stat cache 条目 TTL，单位秒。 | cache 开启后，超过 TTL 的条目会重新读取。 |
| `lake_tablet_stat_cache_clean_interval_sec` | `300` | BE/CN tablet stat cache 后台清理间隔，单位秒。 | cache 开启后，清理线程按该间隔移除过期条目。 |
| `lake_metadata_fetch_thread_count` | `3` | BE/CN lake metadata fetch 线程数；`get_tablet_stats` 内部 tablet task 受这个线程池限制。 | BE/CN bvar 或日志显示 `lake_metadata_fetch` active/queue 上限变化。 |

开启示例：

```sql
ADMIN SET FRONTEND CONFIG ("lake_tablet_stat_progress_log_interval_ms" = "10000");
```

`lake_tablet_stat_progress_log_interval_ms` 只增加观测日志，不改变采集线程池大小、
in-flight 限流或默认调度周期。FE 参数使用 `ADMIN SET FRONTEND CONFIG` 动态调整；
BE/CN `CONF_m*` 参数按 StarRocks 配置规则在对应节点配置文件或动态配置入口调整，
具体以参数是否支持 mutable 为准。

## FE 测试

先跑和改动对应的 FE 测试。当前相关用例：

- `LocalMetastoreShardCleanupTest`：3 个用例。
- `TabletStatMgrTest`：27 个用例（2026-07-07 单独验证通过）。

标准命令：

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; \
    export PYTHON=python3; \
    mvn -f fe/pom.xml -pl fe-core -Dtest=LocalMetastoreShardCleanupTest,TabletStatMgrTest test'
```

`fe/fe-core/pom.xml` 默认使用 `python`，在 CentOS 7 dev-env 中对应 Python 2.7。
`gen_build_version.py` 和 `gensrc/script/gen_functions.py` 需要 Python 3；若漏掉
`PYTHON=python3`，生成源码会缺失 `com.starrocks.common.Version` 和
`com.starrocks.builtins`，随后 `fe-core` 编译失败。

如果 Shield 插件源码变化，补跑插件测试：

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; mvn -pl fe/fe-plugin-shield test'
```

## Maven 仓库映射规则

StarRocks 的 Maven 仓库规则来自 Maven 自身配置和 POM profile，不是
`build.sh` 自动指定。当前源码证据：

- `build.sh` 直接调用 `${MVN_CMD}`，没有传入 `--settings`。
- `fe/pom.xml` 定义两个仓库 profile：
  - `custom-env`：当环境变量 `CUSTOM_MAVEN_REPO` 存在时激活。
  - `general-env`：当环境变量 `CUSTOM_MAVEN_REPO` 不存在时激活。
- `deploy/maven-settings.xml` 只定义了一个 mirror：`mirrorOf=central` 指向
  `https://maven.aliyun.com/repository/public`。

本机缓存映射规则：

- `/home/oppo/.m2/repository` 是软链接，指向 `/mnt/data/maven-repo`。
- `/home/oppo/.m2` 本身仍是普通目录，用来放 `settings.xml`、Gradle Enterprise
  缓存等小文件。
- 因此容器命令同时挂载 `/home/oppo/.m2:/root/.m2` 和
  `/mnt/data/maven-repo:/mnt/data/maven-repo`。前者保留 Maven settings 和软链接入口，
  后者保证真实 repository cache 落在 `/mnt/data`。

### 未设置 `CUSTOM_MAVEN_REPO`

Maven 使用 `fe/pom.xml` 的 `general-env`：

| Repository id | URL | 说明 |
| --- | --- | --- |
| `central` | `https://repo.maven.apache.org/maven2/` | 必须保持在仓库列表首位。若 Maven settings 配置了 `mirrorOf=central`，只改写这个仓库。 |
| `cloudera-public` | `https://repository.cloudera.com/repository/public/` | Java CUP 等依赖使用。 |
| `cloudera` | `https://repository.cloudera.com/repository/cloudera-repos/` | Cloudera 依赖使用。 |
| `kunpeng` | `https://mirror.iscas.ac.cn/kunpeng/maven/` | aarch64 上部分 jar 使用。 |

plugin repositories：

| Plugin repository id | URL |
| --- | --- |
| `central` | `https://repo.maven.apache.org/maven2/` |
| `cloudera-public` | `https://repository.cloudera.com/repository/public/` |

如果容器内存在 `/root/.m2/settings.xml`，Maven 会按 settings 的 mirror 规则改写
仓库。`deploy/maven-settings.xml` 的规则只 mirror `central`，不会 mirror
`cloudera-public`、`cloudera`、`kunpeng`。

### 设置 `CUSTOM_MAVEN_REPO`

当设置 `CUSTOM_MAVEN_REPO` 时，`fe/pom.xml` 激活 `custom-env`：

| Repository id | URL |
| --- | --- |
| `custom-nexus` | `${env.CUSTOM_MAVEN_REPO}` |

plugin repository 也使用同一个 `custom-nexus`。标准命令：

```bash
rtk docker run --rm \
  -e CUSTOM_MAVEN_REPO=https://your.nexus.example/repository/maven-public/ \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; BUILD_TYPE=Release ./build.sh --fe -j 28'
```

### 使用 deploy/maven-settings.xml

`deploy/Dockerfile` 会把 `deploy/maven-settings.xml` 复制到
`/root/.m2/settings.xml`。手工 `docker run` 编译不会自动使用这个文件。需要使用
Aliyun mirror 时，显式挂载或复制 settings：

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -v /mnt/data/starrocks/deploy/maven-settings.xml:/root/.m2/settings.xml:ro \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; BUILD_TYPE=Release ./build.sh --fe -j 28'
```

### 本次构建记录

2026-07-03 到 2026-07-04 的手工容器构建命令没有设置 `CUSTOM_MAVEN_REPO`。
构建使用的是容器内 Maven settings 加 `fe/pom.xml` 的 `general-env` 仓库规则。
Maven 日志显示依赖从 `central` 和 `cloudera-repo-releases` 下载。

## FE 编译

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; BUILD_TYPE=Release ./build.sh --fe -j 28'
```

2026-07-03 实际结果：

```text
Successfully build StarRocks √ Frontend
```

FE 构建期间修过两个 4.1.1 API/build 阻塞：

1. `MetaUtils.getRangeDistributionColumns` 缺少当前分支调用需要的
   `(OlapTable, long)` 兼容入口。
2. `fe-plugin-shield` 需要固定输出名为 `fe-plugin-shield-1.0.0.jar`，并适配
   当前 4.1.1 的 authentication/access-controller API。

## BE 测试

`StarOSWorkerTest` 编译进 `starrocks_test`，不是 `starrocks_dw_test`。如果误用
`starrocks_dw_test --gtest_filter="StarOSWorkerTest.*"`，输出会是 `0 tests`，
这不是有效验证。

编译并运行 `StarOSWorkerTest`：

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; \
    export CC=/opt/rh/gcc-toolset-10/root/usr/bin/gcc; \
    export CXX=/opt/rh/gcc-toolset-10/root/usr/bin/g++; \
    export PATH=/opt/rh/gcc-toolset-10/root/usr/bin:$PATH; \
    export STARROCKS_HOME=/mnt/data/starrocks; \
    cmake -S be -B be/ut_build_ASAN -DMAKE_TEST=ON -DCMAKE_BUILD_TYPE=ASAN -DWITH_STARCACHE=ON -DUSE_STAROS=ON; \
    cmake --build be/ut_build_ASAN --target starrocks_test -j 8'

rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; \
    rm -rf /tmp/starrocks-ut-home; \
    mkdir -p /tmp/starrocks-ut-home/conf /tmp/starrocks-ut-home/log /tmp/starrocks-ut-home/lib/udf; \
    cp /mnt/data/starrocks/output/be/conf/be_test.conf /tmp/starrocks-ut-home/conf/be_test.conf; \
    export STARROCKS_HOME=/tmp/starrocks-ut-home; \
    export UDF_RUNTIME_DIR=/tmp/starrocks-ut-home/lib/udf; \
    export ASAN_OPTIONS=detect_leaks=0; \
    export LD_LIBRARY_PATH=/opt/rh/gcc-toolset-10/root/usr/lib64:/var/local/thirdparty/installed/open_jdk/lib/server:/var/local/thirdparty/installed/lib:/var/local/thirdparty/installed/lib64:${LD_LIBRARY_PATH:-}; \
    /mnt/data/starrocks/be/ut_build_ASAN/test/starrocks_test --gtest_filter="StarOSWorkerTest.*"'
```

2026-07-08 实际结果：

```text
[==========] 7 tests from 1 test suite ran.
[  PASSED  ] 7 tests.
```

BE 的 `tablet_manager_test` 需要 `test_main.cpp` 初始化 StarRocks 测试环境。
当前 `be/test/CMakeLists.txt` 已有独立目标：

```text
tablet_manager_test
```

先编译测试目标：

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; \
    export CC=/opt/rh/gcc-toolset-10/root/usr/bin/gcc; \
    export CXX=/opt/rh/gcc-toolset-10/root/usr/bin/g++; \
    export PATH=/opt/rh/gcc-toolset-10/root/usr/bin:$PATH; \
    export STARROCKS_HOME=/mnt/data/starrocks; \
    cmake -S be -B be/ut_build_ASAN -DMAKE_TEST=ON -DCMAKE_BUILD_TYPE=ASAN -DWITH_STARCACHE=ON -DUSE_STAROS=ON; \
    cmake --build be/ut_build_ASAN --target tablet_manager_test -j 8'
```

再运行目标用例：

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; \
    rm -rf /tmp/starrocks-ut-home; \
    mkdir -p /tmp/starrocks-ut-home/conf /tmp/starrocks-ut-home/log /tmp/starrocks-ut-home/lib/udf; \
    cp /mnt/data/starrocks/output/be/conf/be_test.conf /tmp/starrocks-ut-home/conf/be_test.conf; \
    export STARROCKS_HOME=/tmp/starrocks-ut-home; \
    export UDF_RUNTIME_DIR=/tmp/starrocks-ut-home/lib/udf; \
    export ASAN_OPTIONS=detect_leaks=0; \
    export LD_LIBRARY_PATH=/opt/rh/gcc-toolset-10/root/usr/lib64:/var/local/thirdparty/installed/open_jdk/lib/server:/var/local/thirdparty/installed/lib:/var/local/thirdparty/installed/lib64:/var/local/thirdparty/installed/jemalloc/lib-shared:${LD_LIBRARY_PATH:-}; \
    FILTER="*create_tablet_readback_check_detects_unpersisted_metadata*:*create_tablet_readback_check_disabled*:*lake_tablet_stat_cache*:*get_single_tablet_metadata_bundle_reuse*:*get_tablet_metadata_with_bundle_cache_skips_legacy_probe*:*get_tablet_metadata_with_bundle_cache_falls_back_to_legacy_metadata*:*bundle_metadata_cache_loads_once_for_concurrent_requests*"; \
    /mnt/data/starrocks/be/ut_build_ASAN/test/storage/lake/tablet_manager_test --gtest_filter="$FILTER"'
```

2026-07-04 实际结果：

```text
[==========] 2 tests from 1 test suite ran.
[  PASSED  ] 2 tests.
```

2026-07-06 实际结果：

```text
tablet_manager_test --gtest_filter="*lake_tablet_stat_cache*:*get_single_tablet_metadata_bundle_reuse*:*bundle_metadata_cache_loads_once_for_concurrent_requests*"
[==========] 4 tests from 1 test suite ran.
[  PASSED  ] 4 tests.

lake_service_test --gtest_filter="LakeServiceTest.test_get_tablet_stats:LakeServiceTest.test_get_tablet_stats_cache:LakeServiceTest.test_get_tablet_stats_cache_disabled_by_config"
[==========] 3 tests from 1 test suite ran.
[  PASSED  ] 3 tests.
```

2026-07-08 15:19 实际结果：

```text
tablet_manager_test --gtest_filter="LakeTabletManagerTest.get_single_tablet_metadata_bundle_reuse:LakeTabletManagerTest.get_tablet_metadata_with_bundle_cache_skips_legacy_probe:LakeTabletManagerTest.get_tablet_metadata_with_bundle_cache_falls_back_to_legacy_metadata"
[==========] 3 tests from 1 test suite ran.
[  PASSED  ] 3 tests.
```

注意事项：

- 旧的 `starrocks_dw_test --gtest_filter=...` 二进制没有包含新用例时，会返回
  `0 tests`。这不是有效验证。
- 使用 `ADD_BE_TEST` 生成的独立用例会链接 `gtest_main`，没有初始化本测试需要的
  StarRocks 环境；因此 `tablet_manager_test` 显式使用 `test_main.cpp`。

## BE 编译

不要默认使用 `-j 28`。2026-07-03 的 `-j 28` 编译出现以下硬证据：

- 多个 `cc1plus` 进程处于 `D` 状态。
- CPU 利用率低。
- 磁盘读取量持续增长。
- 编译输出长时间无进展。

结论：瓶颈是磁盘 I/O，不是 CPU。停止 `-j 28` 后检查 0 字节对象文件：

```bash
rtk proxy bash -lc 'find be/build_Release -name "*.o" -size 0 -print | tee /tmp/starrocks-zero-objects.txt | head -50; echo count=$(wc -l < /tmp/starrocks-zero-objects.txt)'
```

实际结果：

```text
count=0
```

标准 BE Release 编译命令：

```bash
rtk docker run --rm \
  -v /home/oppo/.m2:/root/.m2 \
  -v /mnt/data/maven-repo:/mnt/data/maven-repo \
  -v /mnt/data/starrocks:/workspace \
  -v /mnt/data/starrocks:/mnt/data/starrocks \
  -w /mnt/data/starrocks \
  starrocks/dev-env-centos7:4.1-latest \
  bash -lc 'set -euo pipefail; export PYTHON=python3; BUILD_TYPE=Release ./build.sh --be --enable-shared-data -j 8'
```

`--enable-shared-data` 会让 `build.sh` 传入 `-DUSE_STAROS=ON`。编译后用
CMakeCache 验证：

```bash
rtk bash -lc 'grep -n "USE_STAROS\\|WITH_STARCACHE" be/build_Release/CMakeCache.txt'
```

2026-07-04 实际结果：

```text
USE_STAROS:BOOL=ON
WITH_STARCACHE:BOOL=ON
```

2026-07-04 实际结果：

```text
Successfully build StarRocks √ Backend
StartTime:2026-07-03 20:06:58, EndTime:2026-07-03 20:42:00, TotalTime:2102s
```

构建过程中会进入 Java extensions Maven reactor。该阶段成功不代表要替换
`java-extensions` 产物；只有 `java-extensions/**` 有源码变更时才替换相关 jar。

BE 输出路径：

```text
/mnt/data/starrocks/output/be/lib/starrocks_be
```

`build.sh --be` 会生成剥离后的 `output/be/lib/starrocks_be` 和
`output/be/lib/starrocks_be.debuginfo`。替换运行物料时只替换
`output/be/lib/starrocks_be`。

## 替换 Docker 物料

设置路径：

```bash
root=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64
material=$root/StarRocks-4.1.1
backup_dir=$root/_backups_$(date +%Y%m%d%H%M%S)
mkdir -p "$backup_dir"
```

替换 BE 单文件：

```bash
rtk bash -lc 'set -euo pipefail
src=/mnt/data/starrocks/output/be/lib/starrocks_be
dst=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/be/lib/starrocks_be
backup_dir=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_$(date +%Y%m%d%H%M%S)
mkdir -p "$backup_dir"
mode=$(stat -c %a "$dst")
owner=$(stat -c %U:%G "$dst")
cp -a "$dst" "$backup_dir/"
install -m "$mode" "$src" "$dst"
chown "$owner" "$dst"
sha256sum "$src" "$dst"'
```

替换 FE jar 时同样只复制白名单文件，不复制整个 `fe/lib`。

```bash
rtk bash -lc 'set -euo pipefail
src_root=/mnt/data/starrocks/output/fe/lib
dst_root=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/fe/lib
backup_dir=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/_backups_$(date +%Y%m%d%H%M%S)/fe-lib
mkdir -p "$backup_dir"
for name in fe-core-4.1.1.jar fe-spi-4.1.1.jar fe-plugin-shield-1.0.0.jar; do
  src="$src_root/$name"
  dst="$dst_root/$name"
  cp -a "$dst" "$backup_dir/"
  mode=$(stat -c %a "$dst")
  owner=$(stat -c %U:%G "$dst")
  install -m "$mode" "$src" "$dst"
  chown "$owner" "$dst"
  sha256sum "$src" "$dst"
done'
```

FE 物料中如果存在旧 `*-main.jar`，直接删除，不移动进镜像上下文：

```bash
rtk bash -lc 'set -euo pipefail
dst_root=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/fe/lib
sudo rm -f "$dst_root"/fe-*-main.jar "$dst_root"/spark-dpp-main.jar
find "$dst_root" -maxdepth 1 \( -name "fe-*-main.jar" -o -name "spark-dpp-main.jar" \) -print'
```

如果当前源码改动没有涉及 `fe-spi/**`，不要机械替换 `fe-spi-4.1.1.jar`。
2026-07-04 的物料已经记录了该 jar 的实际替换结果；后续应重新按源码变更判断。

## 替换后验证

1. 校验 sha256：

```bash
rtk bash -lc 'sha256sum \
/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar \
/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/fe/lib/fe-spi-4.1.1.jar \
/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/fe/lib/fe-plugin-shield-1.0.0.jar \
/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1/be/lib/starrocks_be'
```

2. 扫描最近修改的运行产物：

```bash
rtk bash -lc 'base=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1; \
find "$base/fe/lib" "$base/be/lib" -maxdepth 1 -type f -newermt "2026-07-03 23:00:00" -printf "%TY-%Tm-%Td %TH:%TM:%TS %s %p\n" | sort'
```

2026-07-04 期望范围：

```text
StarRocks-4.1.1/fe/lib/fe-spi-4.1.1.jar
StarRocks-4.1.1/fe/lib/fe-core-4.1.1.jar
StarRocks-4.1.1/fe/lib/fe-plugin-shield-1.0.0.jar
StarRocks-4.1.1/be/lib/starrocks_be
```

3. 扫描物料树内备份污染：

```bash
rtk bash -lc 'material=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1; \
find "$material" \( -name "*.bak-*" -o -name "lib.bak*" -o -name "lib.changed-backup*" \) -print | sort'
```

期望输出为空。

4. 校验没有旧 FE `*-main.jar`：

```bash
rtk bash -lc 'base=/home/service/var/starrocks/docker/starrocks-4.1.1-centos/starrocks-4.1.1-centos-amd64/StarRocks-4.1.1; \
find "$base/fe/lib" -maxdepth 1 \( -name "fe-*-main.jar" -o -name "spark-dpp-main.jar" \) -print'
```

期望输出为空。

## 已遇到的问题和处理方式

| 问题 | 证据 | 处理 |
| --- | --- | --- |
| FE Maven 编译慢 | Maven 配置在 `/home/oppo/.m2`，实际 localRepository 是 `/mnt/data/maven-repo`；只挂错 `.m2` 或漏挂 localRepository 都会导致依赖重新下载 | 所有手工 `docker run` 编译/测试命令同时加 `-v /home/oppo/.m2:/root/.m2` 和 `-v /mnt/data/maven-repo:/mnt/data/maven-repo`。 |
| FE 全量 `lib` 替换会污染运行闭包 | 无源码或 ABI 证据的 jar 版本大范围变化，会让 classpath 结果不可控；2026-07-06 的 thrift/netty 更新有 `pom.xml` 和 `javap` 证据，不属于机械全量替换 | 只替换白名单 jar；runtime 依赖必须逐项给出版本证据和 `javap`/日志证据。 |
| FE 旧 `*-main.jar` 导致 Java ABI 冲突 | `test-01-fe-1` 日志出现 `ShowStmt.getPredicate()` 的 `NoSuchMethodError`；运行进程 `CLASSPATH` 中 `fe-parser-main.jar` 排在 `fe-parser-4.1.1.jar` 前；`javap` 证明旧 jar 返回 `Predicate getPredicate()`，当前版本化 jar 返回 `Expr getPredicate()` | 删除 `fe-*-main.jar` 和 `spark-dpp-main.jar`，同步物料前后都执行空扫描。 |
| BE `-j 28` 编译慢 | 多个编译进程 `D` 状态，CPU 低，磁盘读量持续增长；2026-07-08 16:32 在原始 Docker 修复后同一镜像用 `-j 28` 编译成功，`TotalTime:1457s` | 先用 `ps`/`iostat` 判断是否仍在推进；只有长期无进展且存在空 archive 或坏中间产物时才降并发并清理中间产物。 |
| BE ASAN 独立测试链接出现大量 undefined reference | `libExprs.a` 和 `libRuntime.a` 只有 8 字节，`nm -C` 查不到 `ExprContext::open`、`RuntimeState::RuntimeState`、`AggStateDesc::from_protobuf` 等实现符号 | 删除空 archive，避免触发 CMake 重新生成后执行 `cmake --build be/ut_build_ASAN --target Exprs Runtime -j 4`，确认 `libExprs.a`/`libRuntime.a` 恢复到非空大文件后再重建测试目标。 |
| `starrocks_dw_test` 没有运行到新增用例 | 过滤器返回 `0 tests` | 新增独立 `tablet_manager_test` 目标并使用 `test_main.cpp`。 |
| `ADD_BE_TEST` 目标没有初始化测试环境 | `config::storage_root_path` 为空，测试 SetUp abort | 手写 `ADD_EXECUTABLE(tablet_manager_test test_main.cpp ...)`。 |
| BE build 成功日志出现后 PTY 未立即退出 | Docker 状态显示容器 `ExitCode=0`，随后原会话返回 0 | 等外层 `build.sh` 退出码；不要只凭 Maven reactor 成功判断。 |
| 原始 Docker daemon 启动失败 | `dockerd` 日志显示 `error while opening volume store metadata database (/mnt/data/docker-data/volumes/metadata.db): timeout`；旧 `dockerd` PID 处于 zombie 且保留 `metadata.db` FLOCK；`getent passwd docker` 因 `/etc/group` 有 `docker:x:983:oppo` 但 `/etc/passwd` 缺少 `docker` 用户而卡住 | 备份 `/etc/passwd` 后补齐 `docker:x:983:983:Docker daemon:/nonexistent:/usr/sbin/nologin`；复制并替换 `metadata.db` inode，保留旧 inode 为 `metadata.db.locked-by-zombie-20260708155904`，新旧 SHA 均为 `6e93679a2e1d0b1552ccc14aaff5b9cff02d37684247c1c3b39b07b05436d271`；等待/清理 D 态旧进程后用 systemd 启动原始 Docker，确认 `root=/mnt/data/docker-data`。 |
| Docker run 已成功但客户端短暂不返回 | `build.sh` 在 16:32:15 打印 Backend 成功；Docker 日志同一时间记录 `topic=/tasks/delete`；`docker rm -f` 返回 `removal ... is already in progress`；随后原 `docker run` 会话返回退出码 0 | 先查 containerd/Docker 状态并等待原会话退出；不要立即重启 daemon 或切换临时 data-root。 |
| 备份目录留在物料树内 | `find StarRocks-4.1.1 -name "*.bak-*"` 有输出 | 移到物料树外 `_backups_YYYYMMDD...`。 |

## 提交前检查

```bash
rtk git diff --check
rtk git status --short
rtk git diff --stat
```

提交时只暂存相关源码、测试和文档。不要暂存：

- `.kiro/`
- `curvine-article/`
- 未确认属于本次任务的事故分析草稿
- 构建输出目录
- Docker 物料目录

## 成功标准

一次 4.1.1 修复交付至少满足：

1. 对应 FE/BE 测试通过。
2. FE 和 BE 在 `starrocks/dev-env-centos7:4.1-latest` 中编译成功。
3. 替换文件在白名单内。
4. 替换后 sha256 与构建产物一致。
5. 物料树内没有备份目录或临时文件。
6. change log 记录 commit、参数、产物映射和 sha256。
