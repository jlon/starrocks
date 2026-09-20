// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <list>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>

namespace starrocks::lake {

// Standalone CN-side cache of get_tablet_stats results. Key is (tablet_id, version, accurate),
// value is the row count, data size, and count fast-path safety. A lake tablet's visible version
// is immutable, so a cached value is always valid for its key; the TTL only governs memory
// reclamation, never correctness. It is a separate cache and is never inserted into the query
// metadata cache, preserving the fill_meta_cache=false design for background stat collection.
//
// It caches the final stat rather than tablet metadata, so a hit lets get_tablet_stats skip both
// the bundle metadata read from object storage and the rowset/delvec computation. This mainly helps
// FE restart/upgrade, multi-FE and in-round-retry re-scans, where FE in-memory dataSizeUpdateTime is
// lost or unshared. (Steady-state re-scans on one FE are already skipped by the FE-side stale check.)
//
// Automatic cleanup is two-fold and never lets memory grow without bound:
//   1. every insert evicts least-recently-used entries to keep the entry count within capacity
//      (a hard bound), and
//   2. a background thread periodically drops expired entries.
// The cache is controlled by enable_lake_tablet_stat_cache and lake_tablet_stat_cache_capacity.
// Disabling the switch or setting capacity to 0 makes lookup miss and insert a no-op.
//
// It deliberately uses a small self-contained LRU map instead of the shared DynamicCache to avoid
// pulling heavy headers into every get_tablet_stats translation unit.
class LakeTabletStatCache {
public:
    struct Value {
        int64_t num_rows = 0;
        int64_t data_size = 0;
        bool count_fast_path_safe = false;
    };

    LakeTabletStatCache();
    ~LakeTabletStatCache();

    LakeTabletStatCache(const LakeTabletStatCache&) = delete;
    LakeTabletStatCache& operator=(const LakeTabletStatCache&) = delete;

    // Looks up a cached stat. Returns nullopt on a miss, on an expired entry, or when disabled.
    std::optional<Value> lookup(int64_t tablet_id, int64_t version, bool accurate);

    // Inserts or refreshes a stat with the configured TTL. A no-op when the cache is disabled.
    void insert(int64_t tablet_id, int64_t version, bool accurate, int64_t num_rows, int64_t data_size,
                bool count_fast_path_safe);

    // Removes expired entries. Exposed for tests; also run periodically by the background cleaner.
    void clean_expired();

    // Stops the background cleaner. Idempotent; also called by the destructor.
    void stop();

    int64_t hit_count() const { return _hit_count.load(std::memory_order_relaxed); }
    int64_t miss_count() const { return _miss_count.load(std::memory_order_relaxed); }
    size_t size() const;

private:
    struct Entry {
        std::string key;
        Value value;
        int64_t expire_ms;
    };

    static bool enabled();
    static int64_t capacity();
    static int64_t ttl_ms();
    static int64_t now_ms();
    static std::string make_key(int64_t tablet_id, int64_t version, bool accurate);

    void clean_loop();
    void evict_to_capacity_locked();

    mutable std::mutex _mutex;
    std::list<Entry> _lru;                                              // front = most recently used
    std::unordered_map<std::string, std::list<Entry>::iterator> _index; // key -> position in _lru

    std::atomic<int64_t> _hit_count{0};
    std::atomic<int64_t> _miss_count{0};

    std::atomic<bool> _stopped{false};
    std::mutex _clean_mutex;
    std::condition_variable _clean_cv;
    std::thread _clean_thread;
};

} // namespace starrocks::lake
