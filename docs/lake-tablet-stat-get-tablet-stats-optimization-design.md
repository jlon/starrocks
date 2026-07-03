# Lake Tablet Stat 与 get_tablet_stats 加速设计

日期：2026-07-06
版本：StarRocks 4.1.1（`branch-4.1.1`，OPPO fork）
场景：存算分离，`SHOW DATA` 大量表/分区显示 0，FE `TabletStatMgr` 并行采集已开启，CN `lake_metadata_fetch` 线程池被打满。

## 0. 证据标准

本文只把三类信息当作结论：当前源码、upstream PR/commit 记录、线上日志或指标。源码证据标注到文件和行号；线上指标未采集到的地方只写“证据边界”，不把推断写成结论。

## 1. 结论摘要

`SHOW DATA` 在 shared-data/lake 表上只读 FE 内存中的 tablet 统计字段，不会在执行 SQL 时同步请求 CN。CN 也不会主动通过 tablet report 把 lake tablet size 上报给 FE。lake 表的 `SHOW DATA` 依赖 FE `TabletStatMgr` 周期性主动调用 CN `LakeService.get_tablet_stats`，再把返回值写回 `LakeTablet.dataSize/rowCount`。

当前 `fill_meta_cache=false` 是 upstream 有意设计，不是配置错误。`lake_service.cpp` 明确注释：后台 stat collection 不填缓存，避免污染热查询 cache。对应单测断言 `get_tablet_stats` 后 metadata 不应进入 metacache。

源码证明对象存储/远端 metadata 读取在 `get_tablet_stats` 关键路径上：CN 会读取 tablet metadata 或 bundle tablet metadata，且 `fill_data_cache=false` 会跳过本地 cache 填充；PK accurate 模式还会读取 delete vector。线上已经证明 `lake_metadata_fetch` 线程池被打满，但要把耗时唯一归因到 OSS，必须同时看到 bundle metadata 或 delvec 的慢日志/指标。

upstream 已有的 `#36973/#69548/#71672` 解决了“不污染 cache”“非 PK 不读 delvec”“PK accurate 不重复加载 metadata”等问题。当前源码已经包含这些修复。它们没有解决 FE 按 partition 发起海量小请求的问题，也没有引入 `get_tablet_stats` 专用缓存。

推荐的代码方向是两步：先在 FE 按 CN 聚合 stale tablets 并批量发送 `get_tablet_stats`，减少 RPC 数和 CN 队列抖动；再在 CN 内增加 request-local bundle metadata 复用。长期可增加独立的 stat cache，但不要把 `fill_meta_cache` 改回 true。

## 2. Upstream PR 状态

| PR/commit | 内容 | 当前 4.1.1 工作区状态 | 对本问题的覆盖 |
| --- | --- | --- | --- |
| `#36973` / `13632b6e605` | `Get tablet stats without filling cache` | 已在历史中 | 确立 `get_tablet_stats` 不填 metadata cache 的设计 |
| `#69548` / `2536a287a80`，backport `f7c18c64b8a` | 降低 shared-data PK tablet stat 开销，增加 `lake_enable_accurate_pk_row_count` 和慢日志 | 已在历史中 | 避免非 PK 读 delete vector，提供诊断日志 |
| `#71672` / `ad22bb9dfaa`，backport `3ac01dc9092` | PK accurate 模式复用已加载 tablet metadata | 已在历史中 | 避免每个 rowset 再次加载 metadata |

本地 `git log --all --grep='get_tablet_stats'` 只找到上述相关修复。没有找到按 CN 批量聚合请求或 stat 专用缓存的 upstream PR。

## 3. 当前数据流

### 3.1 `SHOW DATA` 读取 FE 内存

`SHOW DATA` 的入口在 `fe/fe-core/src/main/java/com/starrocks/qe/ShowExecutor.java:1682`。无表名时读取 `olapTable.getDataSize()`；指定表名时按 index 聚合 `mIndex.getDataSize()` 和 `mIndex.getRowCount()`。

调用链如下：

```text
ShowExecutor.visitShowDataStatement
  -> OlapTable.getDataSize()
  -> Partition.getDataSize()
  -> PhysicalPartition.storageDataSize()
  -> MaterializedIndex.getDataSize()
  -> LakeTablet.getDataSize()
```

关键源码：

- `OlapTable.getDataSize()`：`fe/fe-core/src/main/java/com/starrocks/catalog/OlapTable.java:1843`
- `Partition.getDataSize()`：`fe/fe-core/src/main/java/com/starrocks/catalog/Partition.java:219`
- `PhysicalPartition.storageDataSize()`：`fe/fe-core/src/main/java/com/starrocks/catalog/PhysicalPartition.java:610`
- `MaterializedIndex.getDataSize()`：`fe/fe-core/src/main/java/com/starrocks/catalog/MaterializedIndex.java:273`
- `LakeTablet.dataSize/rowCount` 默认 0：`fe/fe-core/src/main/java/com/starrocks/lake/LakeTablet.java:53`

因此 `SHOW DATA=0` 的直接含义是 FE 内存中的 `LakeTablet.dataSize` 仍为 0。它不是实时 CN 探查结果。

### 3.2 local 表和 lake 表走不同统计路径

`TabletStatMgr.runAfterCatalogReady()` 每轮执行三步：拉本地 tablet stat、拉 lake tablet stat、汇总 index row count。入口在 `fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java:182`。

shared-nothing 模式下，FE 调 BE thrift 接口 `get_tablet_stat`，BE 返回本地 `TabletManager` 的 `_tablet_stat_cache`：

- FE 调用：`TabletStatMgr.updateLocalTabletStat()`，`fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java:315`
- BE 接口：`be/src/service/service_be/backend_service.cpp:51`
- BE cache：`be/src/storage/tablet_manager.cpp:636`

shared-data 模式下，FE 不使用 BE tablet report。`ReportHandler.tabletReport()` 在 shared-data 模式直接返回：

```java
if (RunMode.isSharedDataMode()) {
    return;
}
```

源码位置：`fe/fe-core/src/main/java/com/starrocks/leader/ReportHandler.java:467`。

lake 表统计由 FE 主动探查 CN：

- `TabletStatMgr.updateLakeTabletStat()`：`fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java:363`
- 并行模式提交 partition-level job：`fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java:536`
- 每个 job 内按当前 partition 的 tablet 所属 CN 分组并发 RPC：`fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java:933`
- CN 响应后更新 `LakeTablet.dataSize/rowCount/updateTime`：`fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java:991`

### 3.3 调度周期和未完成行为

默认采集周期是 300 秒：

```java
public static long tablet_stat_update_interval_second = 300;
```

源码位置：`fe/fe-core/src/main/java/com/starrocks/common/Config.java:1782`。

`TabletStatMgr` 继承 `Daemon`。`Daemon.run()` 每轮先完整执行 `runOneCycle()`，然后 `Thread.sleep(getInterval())`。源码位置：`fe/fe-core/src/main/java/com/starrocks/common/util/Daemon.java:95`。

结论：如果一轮 lake tablet stat 采集超过 5 分钟，下一轮不会并发叠加；当前轮结束后才 sleep 300 秒，再进入下一轮。

### 3.4 CN `get_tablet_stats` 执行路径

CN 入口在 `be/src/service/service_be/lake_service.cpp:1109`。流程如下：

```text
LakeServiceImpl::get_tablet_stats
  -> 使用 ExecEnv.lake_metadata_fetch_thread_pool()
  -> 每个 tablet_info 提交一个内部 task
  -> TabletManager.get_tablet_metadata(tablet_id, version, fill_meta_cache=false, fill_data_cache=false)
  -> 遍历 rowsets 计算 num_rows/data_size
  -> PK accurate 模式读取 delete vector
  -> 返回 TabletStatResponse
```

线程池由 `lake_metadata_fetch_thread_count` 控制，默认 3：

- 配置：`be/src/common/config.h:1381`
- 初始化：`be/src/runtime/exec_env.cpp:648`

线上看到 `active_threads=12/12 queue=35`，与该线程池被打满一致。

### 3.5 bundle metadata 读取路径

当 tablet metadata 采用 bundle 存储时，`TabletManager.get_single_tablet_metadata()` 会读取 bundle 文件，再从中抽取单个 tablet metadata。关键源码：

- 先查 metacache：`be/src/storage/lake/tablet_manager.cpp:691`
- 生成 bundle path：`be/src/storage/lake/tablet_manager.cpp:697`
- `fill_data_cache=false` 转为 `skip_fill_local_cache=true`：`be/src/storage/lake/tablet_manager.cpp:705`
- 通过 singleflight 合并同一真实路径的并发读：`be/src/storage/lake/tablet_manager.cpp:714`
- 实际读取整份 bundle：`input_file->read_all()`，`be/src/storage/lake/tablet_manager.cpp:716`
- 解析后抽取目标 tablet metadata：`be/src/storage/lake/tablet_manager.cpp:723`

已有 bvar：

- `lake_read_bundle_tablet_meta_cnt`
- `lake_read_bundle_tablet_meta_real_access_cnt`
- `lake_read_bundle_tablet_meta_latency`

源码位置：`be/src/storage/lake/tablet_manager.cpp:71`。

当前实现只在“同一时刻读取同一真实路径”时合并，不跨请求保存 bundle 内容。

## 4. `fill_meta_cache=false` 的真实原因

`get_tablet_stats` 里有明确注释：

```cpp
// Don't fill caches to avoid polluting hot query caches from background stat collection.
lake::CacheOptions cache_opts{.fill_meta_cache = false, .fill_data_cache = false};
```

源码位置：`be/src/service/service_be/lake_service.cpp:1146`。

测试也把这个行为固定下来：

- `be/test/service/lake_service_test.cpp:3535`：说明不填 metadata cache 是为了避免 stat workload 污染 LRU metacache。
- `be/test/service/lake_service_test.cpp:3746`：断言 stat 请求后 metadata 不进入 metacache。

因此不应把 `fill_meta_cache` 改成 true。那会破坏 upstream 设计，把后台全库统计 workload 混入查询热 metadata cache。

## 5. 性能瓶颈拆解

### 5.1 FE 海量小请求

当前 FE 以 physical partition 为 job 粒度。每个 partition job 内只对该 partition 的 tablet 按 CN 分组。源码位置：`fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java:933`。

在线上数据规模下，全库约 76 万 partition、88 万 tablet。大量 partition 只有 1 到 2 个 tablet。即使开启 FE 并行，FE 仍会制造大量小 RPC，并把 CN 的 `lake_metadata_fetch` 线程池持续喂满。

### 5.2 CN metadata fetch 线程池饱和

CN 对每个 `tablet_info` 提交一个内部 task。线程池最大线程数由 `lake_metadata_fetch_thread_count` 控制，默认 3。线上配置 12 后出现 `active_threads=12/12 queue=35`，说明瓶颈已进入 CN metadata fetch 路径。

### 5.3 bundle metadata 远端读取和解析

bundle metadata 路径会 `read_all()` 整个 bundle 文件，再抽取单个 tablet metadata。`fill_data_cache=false` 使读取不填本地 cache。若每个请求只含 1 到 2 个 tablet，singleflight 和 bundle 聚合收益有限。

### 5.4 PK accurate 模式读取 delete vector

`lake_enable_accurate_pk_row_count=true` 时，PK tablet 会读取 delete vector 计算准确 row count。配置注释写明这会从 object storage 读取 delete vector，并显著增加 `get_tablet_stats` RPC 开销：

- 配置说明：`be/src/common/config.h:778`
- 读取路径：`be/src/storage/lake/update_manager.cpp:1843`

这只影响 PK tablet accurate row count；非 PK tablet 已由 upstream PR 避免读 delvec。

## 6. 证据边界：如何定责 OSS

当前证据能钉死两点：

1. `get_tablet_stats` 的 metadata 和 delvec 读取路径会访问远端对象存储。
2. 线上 CN `lake_metadata_fetch` 线程池已经饱和。

当前证据还不能把耗时唯一归因到 OSS。还需要在同一时间窗口采集以下数据：

| 证据 | 判断标准 |
| --- | --- |
| `lake_read_bundle_tablet_meta_latency` | latency 持续升高，且与 FE 慢 partition 时间重合，说明 bundle metadata 读路径慢 |
| `lake_read_bundle_tablet_meta_real_access_cnt / lake_read_bundle_tablet_meta_cnt` | 比值接近 1，说明 singleflight 合并收益低，大量请求都在真实读取 |
| CN `Slow tablet stat collection` 日志 | `is_pk_tablet`、`accurate_mode`、`num_rowsets`、`elapsed_ms` 用来区分 metadata 慢和 PK delvec 慢 |
| `lake_metadata_fetch` active/queue | active 打满且 queue 增长，说明瓶颈在 CN metadata fetch 线程池 |
| 对象存储客户端指标 | 与 bundle/delvec 慢日志同时间抬升，才能把耗时定责到 OSS |

建议临时把 `lake_tablet_stat_slow_log_ms` 从 300000 调低到 10000 或 30000，观察 1 到 2 轮采集后恢复。该参数在 BE 侧，默认 300000 ms，源码位置：`be/src/common/config.h:785`。

## 7. 方案设计

### 7.1 方案 A：FE 按 CN 批量聚合 stale tablets（推荐先做）

目标：把 partition-level 小请求改为 CN-level batch 请求，减少 RPC 数，降低 CN 队列抖动。

现状：

```text
partition job
  -> partition 内按 CN 分组
  -> 每个 CN 一次 get_tablet_stats
```

改造后：

```text
扫描 DB/table/partition
  -> 找出 stale LakeTablet
  -> 按 partition 内的 ComputeNode 分组
  -> 追加到每个 CN 的当前 pending batch
  -> batch 满或追加下一个 partition 会超 batch size 时提交
  -> 受全局 in-flight 限流和 per-CN 单飞调度控制
  -> 响应后按 tablet_id 回写 LakeTablet
```

关键约束：

- 不在持表锁期间等待 CN RPC。扫描阶段只生成轻量引用，RPC 和回写阶段按现有方式更新 tablet volatile 字段。
- 保留 stale 判断：`LakeTablet.dataSizeUpdateTime < visibleVersionTime`。
- 保留已有 `lake_tablet_stat_collect_parallelism` 和 `lake_tablet_stat_max_inflight_tasks` 语义。
- 当前实现采用扫描期流式 batch 构造，由全局 in-flight 上限（`lake_tablet_stat_max_inflight_tasks`）控制 FE 同时提交的任务数，并对同一个 CN 做单飞调度：该 CN 的前一个 batch 完成后才提交下一个 batch。
- batch size 使用配置 `lake_tablet_stat_batch_size` 控制，源码默认值为 `100`。
- **batch 切分必须保持 partition/bundle 局部性**：`file_bundling` 表一个 physical partition 的所有 tablet 共享同一个 bundle metadata 文件，因此同一 partition 的 stale tablet 必须尽量落到同一个 batch，不能被固定 batchSize 切片打散到不同 batch。否则方案 B 的 request-local bundle 复用会失效（同一 bundle 被拆到多个请求里各读一次）。实现上按 partition 先分组，再追加到对应 CN 的当前 pending batch；只有当单个 partition 的 tablet 数超过 batch size 时才跨 batch 拆分同一 partition。
- 进度日志保留 submitted/completed/failed/skipped、requested/updated tablets、in-flight、last submitted/completed partition；per-CN 统计指标属于后续增强项。

收益：

- RPC 数从接近 partition 数下降到 `stale_tablet_count / batch_size`。
- CN 每个 RPC 可以携带更多 tablet，内部线程池能连续处理同一批请求，减少 FE 小请求造成的调度开销。
- 为 CN request-local bundle metadata 复用创造条件（前提是 batch 保持 partition/bundle 局部性）。

收益边界（不要高估）：

- 方案 A 减少的是 RPC 数量和 FE 调度开销，**不增加 CN 处理吞吐**。CN 仍是每个 tablet 一个内部 task 提交到 `lake_metadata_fetch_thread_pool`（`be/src/service/service_be/lake_service.cpp:1131`），并发上限仍由 `lake_metadata_fetch_thread_count` 决定。
- 因此对“升级/重启后 FE 内存 stats 全 0、需要首次全量回填 76 万 partition”这个核心场景，方案 A 不能根本加速：首次回填时每个 partition 的 bundle 至少要被 CN 读一次，天花板在 CN 并发（`lake_metadata_fetch_thread_count`）叠加对象存储读取吞吐。方案 A/B/C 都属于“减少放大”，不改变这个天花板。

风险：

- 单个 batch 太大会增加响应等待时间和失败影响面。用 batch size 和全局 in-flight 控制。
- FE 需要保存 tablet 引用映射。当前实现的内存上界是“当前 partition 内临时分组 + 每个 CN 一个 pending batch + 已提交 in-flight job”，约为
  `O(current_partition_tablets + cn_count * batch_size + max_inflight * batch_size)`，避免全库 stale tablet 常驻大 map。

### 7.2 方案 B：CN request-local bundle metadata 复用（已落地）

目标：同一个 `get_tablet_stats` 请求内，多个 tablet 命中同一 bundle metadata 文件时，只读取和解析一次 bundle。

当前 `TabletManager.get_single_tablet_metadata()` 已有 singleflight，但它只合并并发读同一真实路径。它不保存已解析 bundle，也不跨 tablet 复用解析结果。

2026-07-08 线上验证进一步定位到一个冷启动放大点：`get_tablet_stats` 传入
`BundleMetadataCache` 后，冷 metacache 下仍先走
`get_tablet_metadata(tablet_metadata_location(tablet_id, version), ...)`，即先探测
`.../meta/<tablet_id>_<version>.meta` 旧格式文件。生产 CN 日志中
`FileNotFoundException` 的文件名第一段为非零 tablet id，证明这些失败打开不是
bundle 文件 `0000000000000000_<version>.meta`，而是旧格式 metadata 探测。每个
tablet 一次失败打开会把 Curvine/Jindo metadata open 放大到 80 万级，打满
`lake_metadata_fetch` 线程池。

改造方式：

1. 在 `LakeServiceImpl::get_tablet_stats` 内建立 request-local map。
2. key 使用 bundle real path 或可稳定定位 bundle 的 `(metadata_root_real_path, version)`。
3. value 保存已解析的 `BundleTabletMetadataPB` 或已抽取的 `TabletMetadataPB`。
4. 同一请求内命中相同 key 时直接抽取 tablet metadata，不再 `read_all()`。
5. 请求结束即释放 map，不进入全局 metacache。
6. `get_tablet_metadata(..., BundleMetadataCache*)` 看到非空 `bundle_cache` 时先读
   bundle metadata；只有 bundle 路径返回 `NotFound` 才 fallback 到旧格式
   `<tablet_id>_<version>.meta`。非 `NotFound` 错误直接返回，避免把真实 I/O、
   解析或损坏错误吞掉。

收益：

- 不污染查询 metacache，符合 `#36973` 的设计。
- 减少同一 batch 内重复读取、重复 parse bundle metadata。
- 实现范围在 CN 内部，不改变 RPC 协议。

边界：

- 如果一个 batch 内每个 tablet 都属于不同 bundle，收益有限。
- 该方案依赖方案 A 提高单个请求中 tablet 数量，否则当前 1 到 2 tablet 的 partition 请求没有足够复用空间。

落地实现（已合入）：

- `be/src/storage/lake/tablet_manager.{h,cpp}` 新增 request-scoped `lake::BundleMetadataCache`，缓存 `bundle real path -> {原始 serialized bytes, 已解析 BundleTabletMetadataPB}`；命中时直接从缓存字节抽取单 tablet metadata，跳过 `read_all()` 与 bundle 解析。抽取单 tablet 必须使用原始 bytes（offset/size 指向 bytes 内的 `TabletMetadataPB`），所以缓存同时保留序列化字节与已解析 bundle。
- `get_tablet_metadata`/`get_single_tablet_metadata`（`CacheOptions` 重载）新增尾部默认参数 `BundleMetadataCache* bundle_cache = nullptr`，不影响其它调用方。
- `be/src/service/service_be/lake_service.cpp` 的 `get_tablet_stats` 在 latch 前创建一个 request-local `BundleMetadataCache` 并传入每个 tablet task；`latch.wait()` 保证其生命周期覆盖所有并发 task，请求结束即析构，不进入全局 metacache。
- 与现有 singleflight 叠加，可保证同一请求内同一 bundle 的对象存储读取 ≈ 1 次（singleflight 合并并发窗口、cache 兜底调度错开）。`g_read_bundle_tablet_meta_cnt` 仍按每 tablet 计数，`g_read_bundle_tablet_meta_real_access_cnt` 只在真实读取时计数，`(cnt - real_access_cnt)` 即被合并/复用避免的读取次数；请求结束额外输出 `bundle_reads`/`bundle_reuses` 的 VLOG(2)。
- 测试：`be/test/storage/lake/tablet_manager_test.cpp` 的 `get_single_tablet_metadata_bundle_reuse`（同 bundle 两 tablet：miss=1/hit=1，且不填 metacache）、`get_tablet_metadata_with_bundle_cache_skips_legacy_probe`（bundle-aware 路径不触发旧格式探测）和 `get_tablet_metadata_with_bundle_cache_falls_back_to_legacy_metadata`（旧格式 metadata 仍 fallback 成功）。

### 7.3 方案 C：CN 独立 stat cache（已落地）

目标：缓存 `get_tablet_stats` 的最终结果，而不是缓存查询 metadata。

cache key：

```text
(tablet_id, version, accurate_mode)
```

cache value：

```text
num_rows, data_size, create_time
```

正确性依据：lake tablet visible version 不可变；同一个 `(tablet_id, version)` 的 metadata 语义稳定。PK accurate mode 影响 row count，因此进入 key。

建议配置：

| 参数 | 默认 | 作用 |
| --- | --- | --- |
| `enable_lake_tablet_stat_cache` | `true` | 是否启用 CN 侧 `get_tablet_stats` 结果缓存 |
| `lake_tablet_stat_cache_capacity` | `1048576` | 缓存条目数硬上限；`0` 显式关闭整个 cache |
| `lake_tablet_stat_cache_ttl_sec` | `3600` | 条目 TTL（秒），过期条目由后台清理线程回收 |
| `lake_tablet_stat_cache_clean_interval_sec` | `300` | 后台清理线程扫描过期条目的间隔（秒） |

收益（真实收益场景，避免高估）：

- 主要收益在 **FE 重启/升级后的全量重扫、多 FE 各自采集、单轮内失败重试**。这些场景下 FE 内存的 `LakeTablet.dataSizeUpdateTime` 丢失或未共享，CN stat cache 能让重扫命中缓存、跳过 bundle 读取与统计计算。
- 注意：**同一个 FE 稳态运行下的“重复扫描”已经被 FE 侧 stale 判断消除**——现有并行采集在 `createCollectTabletStatJob` 中用 `dataSizeUpdateTime >= visibleVersionTime` 跳过未变化的 tablet（`fe/fe-core/src/main/java/com/starrocks/catalog/TabletStatMgr.java`），version 不变的表第二轮根本不发 RPC。因此方案 C 不要以“稳态重复扫描”为主要卖点，其价值集中在重启/升级/多 FE/重试。
- 不污染查询 metacache。

风险：

- 首次全量扫描仍要读 metadata。
- 需要内存上限、命中率指标和清理策略。

结论：该方案适合作为第二阶段，不应先于 FE 批量化落地。

落地实现（已合入）：

- `be/src/storage/lake/tablet_stat_cache.{h,cpp}` 新增 `lake::LakeTabletStatCache`：自包含的 LRU + TTL cache，key=`tablet_id_version_accurate`（accurate 维度取查询时的 `config::lake_enable_accurate_pk_row_count`；真实 `accurate_mode` 还需 `is_pk_tablet`，但那要读 metadata 才知道，用 config 值是"查询时即可构造且不会把 accurate 结果错发给 approximate 请求（反之亦然）"的安全超集），value=`{num_rows, data_size}`。
- **自动清理机制（双重，内存有硬上限）**：每次 `insert` 按 LRU 淘汰超出 `lake_tablet_stat_cache_capacity` 的条目（硬上限，内存不会无界增长）；后台清理线程每 `lake_tablet_stat_cache_clean_interval_sec` 秒回收过期条目并同步容量；`lookup` 命中已过期条目时惰性删除。`enable_lake_tablet_stat_cache=false` 或 `capacity=0` 时，`lookup` 直接 miss、`insert` 为空操作。
- 刻意**不复用 `DynamicCache`**：其头文件间接引入 `storage/rowset_update_state.h` 等重依赖，会把 `date`/`UserFunctionCache`/`TypeDescriptor` 等符号带进每个包含它的编译单元并破坏 test 链接；改用仅依赖标准库（`list`/`unordered_map`/`mutex`）的轻量实现（KISS）。
- `get_tablet_stats` 在读 metadata 前查 cache，命中则直接回填 response 并 `return`（跳过 bundle 读取与 rowset/delvec 计算）；miss 计算后写入 cache。cache 独立于查询 metacache，保持 `fill_meta_cache=false`。
- 正确性：lake tablet 的 visible version 不可变，同一 `(tablet_id, version, accurate)` 的 value 恒定，因此 TTL 只用于内存回收、绝不影响正确性。
- 测试：`be/test/storage/lake/tablet_manager_test.cpp` 的 `lake_tablet_stat_cache_default_config` 覆盖默认开启和默认容量；`lake_tablet_stat_cache` 覆盖显式关闭、命中/未命中、version/accurate 维度区分 key、容量硬上限自动淘汰。

### 7.4 方案 D：临时关闭 PK accurate row count

配置：

```text
lake_enable_accurate_pk_row_count=false
```

效果：PK tablet 使用 rowset metadata 中的 `num_dels` 近似值，避免读取 delete vector。源码和配置说明明确该模式会略微高估未 compact 的删除行，但能减少远端 I/O。

适用条件：

- 慢日志证明慢点集中在 `is_pk_tablet=true, accurate_mode=true`。
- 运维接受 `SHOW DATA`/统计行数在 PK 表上的近似误差。

该方案是运维降载手段，不替代代码优化。

### 7.5 方案 E（备选）：CN 按 bundle 一次计算整个 partition 的 tablet stats

目标：把统计粒度从“per-tablet 读一次 metadata”提升到“per-bundle 读一次、算出该 bundle 覆盖的全部 tablet stats”，从源头消除同一 partition 内多 tablet 重复进入 `get_tablet_metadata` 的开销。

依据：`file_bundling` 表一个 physical partition 的所有 tablet metadata 存在同一个 bundle 文件里（`0000000000000000_<version>.meta`）。当前 `get_tablet_stats` 对每个 tablet 提交一个 task，各自调用 `get_tablet_metadata`，靠 `_bundle_tablet_metadata_group` 的 singleflight 合并“同一时刻并发读同一 bundle”。但 singleflight 只在并发窗口重合时才合并；线程池调度错开时，同一 bundle 仍可能被 `read_all()` 多次。

改造方向：

1. CN 侧把同一请求内、同一 bundle real path 的 tablet 归组。
2. 每个 bundle 只读取+解析一次，然后从 `BundleTabletMetadataPB` 的 page map 中依次抽取每个 tablet 的 metadata 并计算 stat。
3. 结果按 tablet_id 回填 response。

与方案 B 的区别：方案 B 是“request-local map 缓存已解析 bundle，命中就不再 read_all”，仍是 per-tablet 驱动；方案 E 是“per-bundle 驱动，一次算全组”，更彻底地避免同 bundle 的重复 metadata 查找与遍历调度。

边界与取舍：

- 收益同样取决于“单个请求内同 partition/同 bundle 的 tablet 数”，因此**依赖方案 A 的 partition/bundle 局部性 batch**。
- 改动面比方案 B 大：改变 `get_tablet_stats` 内部的任务组织粒度（从 per-tablet task 到 per-bundle task），需要重构 `lake_service.cpp` 的 task 提交与 latch 计数逻辑，并保证单个 bundle 读取失败只影响该 bundle 覆盖的 tablet、不影响其他 bundle。
- 因此列为备选：若方案 A + 方案 B 上线后 `lake_read_bundle_tablet_meta_real_access_cnt / lake_read_bundle_tablet_meta_cnt` 仍接近 1（说明 singleflight/request-local 复用收益不足），再评估方案 E。

## 8. 不建议的方案

### 8.1 不建议把 `fill_meta_cache` 改为 true

这会把后台全库统计 workload 写入查询热 metadata cache，违背 `#36973` 的修复目标。单测也把“不污染 metacache”固定成行为契约。

### 8.2 不建议让 `SHOW DATA` 同步探查 CN

`SHOW DATA` 是交互 SQL。若它同步触发全库 CN 探查，在 76 万 partition 场景下会把用户 SQL 变成重型后台任务，并放大 CN metadata fetch 队列。正确方向是让后台采集更快、更可观测，而不是把采集压到查询线程上。

### 8.3 不建议让 CN 主动上报全部 lake tablet stats

lake tablet 不像 shared-nothing replica 那样固定归属于某个 BE。FE 通过 warehouse/compute resource 选择 CN 执行探查。让 CN 主动上报需要定义 ownership、迁移、去重和多 FE 汇聚语义，改动面大，收益不如 FE batch 明确。

## 9. 可观测性设计

FE 侧保留并扩展现有日志：

- `lake_tablet_stat_progress_log_interval_ms`：默认 `-1` 关闭；正数开启进度日志。
- summary 日志：总 submitted/completed/failed/skipped、requested/updated tablets、耗时。
- slow partition/batch 日志：定位 FE 等待慢点。

后续建议：

| 指标/日志 | 说明 |
| --- | --- |
| per-CN submitted/completed batches | 判断热点 CN |
| per-CN in-flight/queue wait | CN-batch 已按 CN 做单飞调度；用于判断 FE 是否仍过量喂给某个 CN |
| batch tablet count 分布 | 验证 batch 是否有效聚合 |
| response updated tablet count | 验证 CN 返回有效数据 |

CN 侧保留并扩展：

- `lake_tablet_stat_slow_log_ms`：单 tablet 慢日志阈值。
- `lake_read_bundle_tablet_meta_*` bvar：bundle metadata 读取次数和耗时。
- `lake_metadata_fetch` 线程池 active/queue 指标。

新增建议：

| 指标 | 说明 |
| --- | --- |
| stat cache hit/miss | 若实现方案 C，用于评估收益 |
| request-local bundle hit/miss | 若实现方案 B，用于证明 bundle 复用有效 |
| per-request tablets/bundles | 判断 FE batch 与 CN bundle 复用是否匹配 |

## 10. 测试计划

FE 测试放在 `fe/fe-core/src/test/java/com/starrocks/catalog/TabletStatMgrTest.java`：

- stale 判断不变：fresh tablet 不进入 batch。
- batch size 生效：给定 N 个 tablet，按配置切分请求。
- 按 CN 分桶：不同 CN 的 tablet 不混发。
- 按 CN 单飞：同一 CN 同时最多一个 batch in-flight，避免 FE 把多个 batch 并发压到同一 CN。
- partition/bundle 局部性：单个 partition 不超过 batch size 时不能被跨 batch 拆分。
- 响应回写：`LakeTablet.dataSize/rowCount/updateTime` 与现有行为一致。
- 失败处理：单个 batch 失败只增加失败计数，不影响其他 batch 回写。

BE 测试放在 `be/test/service/lake_service_test.cpp` 和
`be/test/storage/lake/tablet_manager_test.cpp`：

- request-local bundle 复用不填 metacache。
- 同一请求内同 bundle 多 tablet 只真实读取一次。
- 不同 bundle 正常读取和返回。
- PK accurate/approximate 行为保持现有测试语义。

性能验证：

- 构造大量小 partition、每 partition 1 到 2 tablet。
- 对比改造前后 FE RPC 数、CN `lake_metadata_fetch` queue、总采集耗时。
- 观察 `lake_read_bundle_tablet_meta_real_access_cnt / lake_read_bundle_tablet_meta_cnt` 变化。

## 11. 推进顺序

1. 先开启观测：FE progress log、CN slow log、bundle metadata bvar、thread pool active/queue。
2. ✅ 已落地 FE 按 CN batch（方案 A），并在 2026-07-07 增加 per-CN 单飞调度：同一个 CN 的下一个 batch 必须等待前一个 batch 完成后才提交。开关 `enable_lake_tablet_stat_cn_batch_collection` 当前默认关闭；旧并行开关仍作为关闭 CN batch 后的 partition-level 回退路径。
3. 灰度验证 RPC 数、CN queue、CN restart count 和 Curvine `FileNotFound`/`master load job queue is full` 日志是否下降。test-01 验证证据显示：`lake_tablet_stat_batch_size=100` 已生效（FE 日志 `tablets100>0, tablets512=0`），但无 per-CN 单飞时仍出现 `finished_cn_batch=0`、大量 `Unable to validate object`、CN queue 堆积到单 CN 3000+、CN `exit=139` SIGSEGV 重启；因此 CN-batch 不能默认开启。
4. ✅ 已落地 CN request-local bundle 复用（方案 B）；无对外协议变化，随 `get_tablet_stats` 自动生效。2026-07-08 已补充 bundle-first 修复，消除冷 metacache 下每 tablet 一次旧格式 metadata 失败打开。
5. ✅ 已落地 CN 独立 stat cache（方案 C）；`enable_lake_tablet_stat_cache` 默认 `true`，`lake_tablet_stat_cache_capacity` 默认 `1048576`，覆盖 FE 重启/升级、多 FE 采集和单轮失败重试导致的重复扫描。

方案 A/B/C 已全部落地，方案 C 默认开启；方案 A 保留快速开关但默认关闭，待 per-CN 单飞版本通过生产灰度后再评估是否开启。这个顺序符合 KISS：先保护旧稳定路径，再用小流量验证 CN-batch，最后用结果缓存覆盖重启/升级/多 FE/重试场景。

## 12. 回滚策略

FE batch 功能已落地为可动态调整的开关，默认关闭。灰度时显式打开：

```text
enable_lake_tablet_stat_cn_batch_collection=true
```

发现问题时关闭 `enable_lake_tablet_stat_cn_batch_collection`，这是当前源码默认值。若
`enable_parallel_lake_tablet_stat_collection=true`，关闭 CN batch 后会回到旧 partition-level
并行采集；否则回到串行 partition-level 采集。

CN request-local bundle 复用不改变对外协议。stat cache 默认开启；发现问题时关闭
`enable_lake_tablet_stat_cache`，或把 `lake_tablet_stat_cache_capacity` 调成 0。

运维降载时可临时调小 FE 并行和 in-flight，也可在证据显示 PK delvec 为主因时关闭 `lake_enable_accurate_pk_row_count`。

## 13. 自我审查

- 结论均能对应源码或 upstream PR；OSS 慢没有被写成已定责结论。
- 方案没有回退 `fill_meta_cache=false`，避免破坏 upstream 既有设计。
- 首选方案只改变 FE 请求组织方式，不改变 CN 计算语义，改动面小。
- CN stat cache 有独立开关、容量硬上限、TTL 和 LRU 淘汰；显式关闭 `enable_lake_tablet_stat_cache` 或把容量设为 `0` 即可回退。
- 测试位置按模块划分：FE 测试在 `TabletStatMgrTest`，BE 测试在 `lake_service_test` 和 `tablet_manager_test`，不在核心文件里塞单测。
