# Lake SegmentInit 冷查询优化设计

日期：2026-07-09  
版本：StarRocks 4.1.1（`branch-4.1.1`，OPPO fork）  
场景：存算分离 Lake 表，CN 重启或 Lake MetaCache 冷态后，查询在 `SegmentInit` 阶段耗时 8 到 10 秒。

## 1. 结论

1. 慢点已经定位在 CN 的 Lake `SegmentInit`，主耗时是 `ColumnIteratorInit`，不是 `SegmentRead`。
2. `CACHE SELECT` 在 Lake 查询链路上会填充执行 CN 的 Lake MetaCache；它不保证全部 CN 都被预热。
3. 当前最小闭环方案：保留 `CACHE SELECT` 作为入口，新增“全 CN 元数据预热”能力；数据块预热继续沿用当前调度，不做全 CN 数据复制。
4. DataCache 的一致性哈希 + peer read 设计值得借鉴，但第一版只借鉴“按 key 选择目标 CN”的调度思想，不把 MetaCache 的 `Segment` 运行时对象在 CN 间复制。

## 2. 证据

### 2.1 现场 Profile

现场材料：

- `/home/service/var/openclaw/ebd-starrocks-crm-uat-存算分离慢查询与SegmentInit分析-20260707.md`
- `/home/service/var/openclaw/ebd-starrocks-crm-uat-Lake慢查询-SegmentInit问题简述.md`

关键数据：

| 场景 | 总耗时 | SegmentInit | SegmentRead |
| --- | ---: | ---: | ---: |
| 存算分离冷查询 | 约 10s | 约 8 到 9s | 约 0.14s |
| 存算分离热查询 | 约 0.7s | 约 0.4s | 低 |
| 存算一体对比 | 约 1.2s | 约 0.08s | 约 0.62s |

结论：数据页已被 DataCache 覆盖，冷查询额外耗时来自 CN 内存元数据初始化。

### 2.2 Lake MetaCache 填充链路

源码链路：

- `fe/fe-core/src/main/java/com/starrocks/datacache/DataCacheSelectExecutor.java:151-161`：`CACHE SELECT` 子查询强制打开 DataCache，并设置 `enable_cache_select=true`。
- `be/src/connector/lake_connector.cpp:318-320`：`enable_cache_select` 只影响 `cache_file_only`，不关闭 metadata cache。
- `be/src/storage/tablet_reader_params.h:66`：`LakeIOOptions` 默认 `fill_metadata_cache=true`。
- `be/src/storage/lake/rowset.cpp:720-742`：加载 segment 时把 `lake_io_opts.fill_metadata_cache` 传入 `TabletManager::load_segment`。
- `be/src/storage/lake/tablet_manager.cpp:1478-1500`：按 `FileInfo::cache_key()` 查 `metacache()->lookup_segment`；未命中时构造 `Segment`，`fill_meta_cache=true` 时写入 `cache_segment_if_absent`，随后执行 `segment->open`。

结论：Lake `CACHE SELECT` 会暖执行 CN 的 segment metadata。

### 2.3 CACHE SELECT 不覆盖全部 CN

2026-07-09 在 `starrocks-cluster-sync` 测试：

- 集群：`shared_data`，20 个 CN。
- 表：`codex_meta_cache_exp.cache_select_meta4_20260709`，120 buckets，120 行。
- 操作：等待版本可见后执行 `CACHE SELECT * FROM cache_select_meta4_20260709`。

首轮结果：

| CN | `open_segments` 变化 | MetaCache 变化 |
| --- | ---: | ---: |
| `cn-1` | `1 -> 11` | `66,579,300 -> 66,705,756` |
| `cn-6` | `0 -> 14` | `101,077,854 -> 101,284,053` |
| `cn-19` | `0 -> 16` | `70,303,692 -> 70,537,003` |
| 其他 CN | 未出现同等增长 | 未出现同等增长 |

结论：当前 `CACHE SELECT` 会预热被调度到的 CN；它不是 all-CN MetaCache 预热机制。

## 3. 问题模型

```text
CN MetaCache 冷态
  -> Lake scan 加载 segment footer
  -> 构造 Segment / ColumnReader / index reader
  -> SegmentIterator 初始化 ColumnIterator
  -> ColumnIteratorInit 累计到 8 到 10 秒
```

如果查询被调度到未预热 CN，同一份数据仍会走冷路径。要消除首查抖动，必须让查询可达 CN 在业务查询前完成 metadata 初始化。

## 4. 现阶段可优化逻辑

### 4.1 先拆清计时器边界

`SegmentInit` 不是单一动作：

- `be/src/storage/lake/tablet_reader.cpp:380`：`CreateSegmentIter` 包住 `rowset->read`，包含 `Rowset::load_segments` 和创建 segment iterator。
- `be/src/storage/lake/rowset.cpp:740-746`：当前默认关闭并行时，rowset 内 segment 逐个调用 `TabletManager::load_segment`。
- `be/src/storage/lake/tablet_manager.cpp:1478-1500`：MetaCache 未命中时构造 `Segment`，写入 `cache_segment_if_absent`，再执行 `segment->open`。源码注释明确 `segment->open` 会读取 footer，耗时高。
- `be/src/storage/rowset/segment_iterator.cpp:841-878`：真正的 `SegmentInit` 计时从 `_init_internal` 开始，核心动作包含 `_init_column_iterators`。
- `be/src/storage/rowset/segment_iterator.cpp:1245-1246`：`ColumnIteratorInit` 是 `SegmentInit` 的子计时器。

因此，优化要按 profile 分流：`CreateSegmentIter` 高，优先优化 segment 打开；`ColumnIteratorInit` 高，优先保证预热触发同等列迭代器初始化，并让业务查询落到已预热 CN。

### 4.2 自适应冷 segment 并行打开

当前代码已经有并行框架：

- `be/src/common/config.h:658-660`：`enable_load_segment_parallel=false`，线程池上限 128。
- `be/src/storage/lake/rowset.cpp:710-758`：并行打开 segment 的实现已存在，失败时回退串行。
- `be/src/storage/lake/tablet_reader.cpp:398-423`：开启后还能按 rowset 并行读取。

直接把全局开关长期打开会放大冷启动并发，对 Curvine/OSS 和 CN 内存都有压力。更稳的代码改法是做自适应：

1. 只对 Lake 查询和 metadata warmup 路径生效。
2. 只在 MetaCache miss 数或 segment 数超过阈值时启用。
3. 每个 tablet、每个查询设置并发上限，复用现有 `load_segment_thread_pool`。
4. 线程池提交失败继续走现有串行回退。

这个改动不引入新 RPC，不改变查询结果，只降低单个冷 CN 首次打开大量 segment 的墙钟时间。

已落地实现：`Rowset::should_use_parallel_load`（`be/src/storage/lake/rowset.cpp`）。当 `enable_load_segment_parallel` 关闭时，仅在 `fill_metadata_cache=true`、segment 数 >1、非 segment-range 模式、且 metacache 中存在未命中或未 open 的 segment 时自动启用并行；全部 warm 时回退串行。新增运行时开关 `enable_adaptive_load_segment_parallel`（默认 `true`）作为安全阀，关闭后回到只看 `enable_load_segment_parallel` 的旧行为。segment 级并行使用 `load_segment_thread_pool`，与 rowset 级并行的 `load_rowset_thread_pool` 分离，无同池嵌套阻塞。

### 4.3 冷态并行预初始化 SegmentIterator

`ColumnIteratorInit` 高时，瓶颈在 `SegmentIterator` 的首次初始化：

- `be/src/storage/rowset/segment_iterator.cpp:1947-1950`：`SegmentIterator::_init()` 在第一次 `get_next()` 时触发。
- `be/src/storage/union_iterator.cpp:74-86`：`UnionIterator` 按 child 顺序调用 `get_next()`。
- `be/src/storage/lake/tablet_reader.cpp:583-591`：Lake reader 在多 segment 场景下会把 segment iterators 包成 `UnionIterator`。

因此，在 `CACHE SELECT` 或 metadata warmup 路径上，可以新增一个“预初始化”步骤：

1. 给 `ChunkIterator` 增加默认 no-op 的 `prepare()`。
2. `SegmentIterator::prepare()` 只执行一次 `_init()`，不读取普通数据页。
3. `UnionIterator::prepare()` 对 children 做有界并行 prepare。
4. 只在 `cache_file_only` 或 metadata warmup 路径调用，不改变普通查询的懒加载语义。

这个改动直接压缩多个 segment 的 `ColumnIteratorInit` 墙钟时间。风险边界也清楚：它只提前执行原本第一次 `get_next()` 必然执行的初始化，不跨查询共享 `ColumnIterator`。

已落地实现：`ChunkIterator` 侧新增 `PreparedChunkIterator` 混入接口与 `prepare_chunk_iterator(s)`（`be/src/storage/chunk_iterator.{h,cpp}`），`Union/Merge/Aggregate/Projection/Timed/SegmentIterator` 均实现 `prepare()`；`SegmentIterator::prepare()` 通过 `_ensure_inited()` 触发并缓存一次 `_init()`，`do_get_next` 复用缓存状态，保持"只初始化一次"。仅在 `cache_file_only`（CACHE SELECT/预热）路径调用（`TabletReader::init_collector`），不改变普通查询懒加载语义；新增运行时开关 `enable_lake_segment_parallel_prepare`（默认 `true`）可关闭该预热。并行 prepare 有界（≤8）并复用 `load_segment_thread_pool`，收尾用 `DeferOp` 兜底 wait 全部 future 并把 worker 异常转为 `Status`，避免 worker 引用已析构栈变量。

### 4.4 CACHE SELECT 元数据路径要贴近业务查询

`CACHE SELECT` 会填充执行 CN 的 MetaCache：

- FE 在 `DataCacheSelectExecutor` 强制 `enable_cache_select=true`。
- BE 在 `lake_connector.cpp:318-320` 把它转成 `cache_file_only`。
- `TabletReaderParams` 默认 `fill_metadata_cache=true`。

但 `cache_file_only` 仍会先完成 iterator 初始化，再在 `_do_get_next` 中只 touch data cache range。证据在 `be/src/storage/rowset/segment_iterator.cpp:2015-2059`。所以它能暖 metadata，但只暖执行 CN，且只暖本次计划涉及的列、predicate 和 scan range。

现阶段可先做两件事：

1. 让预热 SQL 使用和业务 SQL 相同的列、谓词和 session 变量，避免只 `CACHE SELECT *` 暖了不同路径。
2. 在 profile 中同时观察 `CreateSegmentIter`、`SegmentInit`、`ColumnIteratorInit`。如果 `ColumnIteratorInit` 仍高，说明预热没有命中业务查询实际 CN 或实际列路径。

### 4.5 不做的局部优化

1. 不用 `lake_metadata_fetch_thread_count` 解决 SQL `SegmentInit`。该线程池用于 `get_tablet_stats/get_tablet_metadatas`，不在本次 scan 的 `SegmentIterator` 初始化链路上。
2. 不跨查询共享 `ColumnIterator`。它绑定本次 schema、predicate、access path、read file、page cache 和 row range，复用会污染查询状态。
3. 不把 DataCache peer read 直接套到 MetaCache。DataCache 传输 byte block；MetaCache 保存 CN 进程内 `Segment`、`ColumnReader` 和本地生命周期对象。

## 5. 设计方案

### 5.1 产品入口

继续使用 `CACHE SELECT`：

```sql
CACHE SELECT ...
PROPERTIES (
    "metadata_scope" = "all_cn"
);
```

默认值：

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `metadata_scope` | `auto` | 保持现有行为，只预热执行查询的 CN |
| `metadata_scope=all_cn` | 显式打开 | 对全部存活 CN 执行 Lake metadata 预热 |

不新增一组复杂开关。运维只需要在冷启动、CN 扩容、重要 BI 表上线前执行一次带 `metadata_scope=all_cn` 的 `CACHE SELECT`。

### 5.2 执行链路

```text
FE 接收 CACHE SELECT
  -> 生成普通 CACHE SELECT 查询，继续预热 DataCache
  -> 解析 Lake scan 目标：tablet / rowset / segment / 版本
  -> 获取当前 warehouse 下存活 CN 列表
  -> 向每个 CN 下发 metadata warmup task
  -> CN 本地调用 Lake segment 加载链路
  -> CN 写入本机 Metacache
  -> FE 汇总每个 CN 的成功数、失败数、耗时、跳过数
```

### 5.3 CN 侧行为

CN 只做本地初始化：

- 复用 `TabletManager::load_segment` 和 `Segment::open`。
- 不读取普通数据页。
- 不把 `Segment` 对象序列化发给其他 CN。
- 对同一 `cache_key` 使用现有 `cache_segment_if_absent` 去重。
- 任务必须有并发上限，避免预热挤占查询线程池。

### 5.4 FE 返回结果

`CACHE SELECT` 需要返回 metadata 预热摘要：

| 字段 | 含义 |
| --- | --- |
| `metadata_scope` | `auto` / `all_cn` |
| `target_cn` | 目标 CN 数 |
| `success_cn` | 完成预热的 CN 数 |
| `failed_cn` | 失败 CN 数 |
| `loaded_segments` | 实际打开 segment 数 |
| `hit_segments` | 已在 MetaCache 中命中的 segment 数 |
| `elapsed_ms` | 总耗时 |

执行失败时，保留失败 CN 明细，便于运维重试单个 CN。

## 6. DataCache Hash Ring 借鉴

DataCache 已有“本地没有缓存，向邻居要”的源码链路：

- `fe/fe-core/src/main/java/com/starrocks/qe/HDFSBackendSelector.java:253-376`：用一致性哈希为 scan range 选择 worker；存在 candidate worker 时，把第一个 candidate 写入 scan range。
- `fe/fe-core/src/main/java/com/starrocks/qe/HDFSBackendSelector.java:397-402`：`candidate_node` 写入 `THdfsScanRange`。
- `gensrc/thrift/PlanNodes.thrift:428`：`THdfsScanRange` 定义 `candidate_node`。
- `be/src/exec/hdfs_scanner/hdfs_scanner.cpp:416-419`：BE 把 `candidate_node` 设置到 `CacheInputStream`。
- `be/src/io/cache_input_stream.cpp:143-163`：本地 DataCache miss 后读取 peer cache，成功后写入本地 cache。
- `be/src/cache/peer_cache_engine.cpp:31-63`：通过 `fetch_datacache` RPC 从 peer 读取 byte block。
- `be/src/service/internal_service.cpp:750-787`：peer 端从本机 `BlockCache` 读取并通过 attachment 返回。

这个设计直接服务于 DataCache byte block。Lake MetaCache 的核心条目是 CN 进程内 `Segment` 运行时对象，包含 `FileSystem`、`TabletSchema`、footer、reader 状态和本地内存生命周期，不能按 DataCache block 的方式直接跨 CN 搬运。

因此本方案借鉴两点：

1. 用稳定 key 选择目标 CN，降低重复调度和定位成本。
2. 使用 peer/fallback 思路做后续增强：内部 owner 模式只预热 owner CN；查询调度优先落到 owner CN。

第一版不暴露 hash ring 产品参数。当前目标是恢复冷查询体验，`metadata_scope=all_cn` 更直接，验证标准也更清晰。

## 7. 不做事项

1. 不做全 CN 数据页复制。现场瓶颈是 `SegmentInit`，不是 `SegmentRead`。
2. 不做 MetaCache 对象广播。运行时对象跨进程复制成本高，失效边界复杂。
3. 不做持久化 MetaCache。重启恢复和版本失效会扩大实现面。
4. 不改变普通查询调度。业务 SQL 仍按现有 scheduler 执行。

## 8. 验证标准

上线前必须完成以下验证：

1. 执行 `CACHE SELECT ... PROPERTIES("metadata_scope"="all_cn")` 后，全部存活 CN 的 `open_segments` 或等价 MetaCache 指标增长。
2. 同一 SQL 首次业务查询的 `SegmentInit` 从 8 到 10 秒下降到 1 到 2 秒区间。
3. `SegmentRead`、DataCache 命中率不回退。
4. 单个 CN 失败时，FE 返回失败明细；其他 CN 的成功预热不回滚。
5. CN 重启后重跑 all-CN 预热，指标恢复。

## 9. 自我 Review

第一轮：方案命中根因。现场和源码都指向 `SegmentInit` / `ColumnIteratorInit`，方案直接预热 CN 本地 Lake metadata，没有把问题误归因到数据页读取。

第二轮：方案保持 KISS。只新增一个用户可见参数 `metadata_scope`，默认保持兼容；不引入多组阈值、比例、模式开关。

第三轮：方案性能边界清晰。全 CN 只预热 metadata，不复制 20 份数据页；DataCache hash ring 作为后续 owner 调度方向保留，不在第一版扩大复杂度。
