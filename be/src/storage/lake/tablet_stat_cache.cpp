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

#include "storage/lake/tablet_stat_cache.h"

#include <algorithm>
#include <chrono>

#include "common/config.h"

namespace starrocks::lake {

LakeTabletStatCache::LakeTabletStatCache() {
    _clean_thread = std::thread([this] { clean_loop(); });
}

LakeTabletStatCache::~LakeTabletStatCache() {
    stop();
}

void LakeTabletStatCache::stop() {
    bool expected = false;
    if (!_stopped.compare_exchange_strong(expected, true)) {
        return;
    }
    _clean_cv.notify_all();
    if (_clean_thread.joinable()) {
        _clean_thread.join();
    }
}

int64_t LakeTabletStatCache::capacity() {
    return std::max<int64_t>(0, config::lake_tablet_stat_cache_capacity);
}

bool LakeTabletStatCache::enabled() {
    return config::enable_lake_tablet_stat_cache && capacity() > 0;
}

int64_t LakeTabletStatCache::ttl_ms() {
    return std::max<int64_t>(0, config::lake_tablet_stat_cache_ttl_sec) * 1000;
}

int64_t LakeTabletStatCache::now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

std::string LakeTabletStatCache::make_key(int64_t tablet_id, int64_t version, bool accurate) {
    // The accurate dimension distinguishes PK accurate row counts from approximate ones; the stat
    // for one (tablet_id, version) is otherwise immutable.
    return std::to_string(tablet_id) + "_" + std::to_string(version) + (accurate ? "_1" : "_0");
}

size_t LakeTabletStatCache::size() const {
    std::lock_guard<std::mutex> l(_mutex);
    return _index.size();
}

std::optional<LakeTabletStatCache::Value> LakeTabletStatCache::lookup(int64_t tablet_id, int64_t version,
                                                                      bool accurate) {
    if (!enabled()) {
        return std::nullopt;
    }
    auto key = make_key(tablet_id, version, accurate);
    std::lock_guard<std::mutex> l(_mutex);
    auto it = _index.find(key);
    if (it == _index.end()) {
        _miss_count.fetch_add(1, std::memory_order_relaxed);
        return std::nullopt;
    }
    // Drop and miss on an expired entry so the TTL is honored even between cleaner runs.
    if (it->second->expire_ms <= now_ms()) {
        _lru.erase(it->second);
        _index.erase(it);
        _miss_count.fetch_add(1, std::memory_order_relaxed);
        return std::nullopt;
    }
    // Move to the front to mark it most-recently-used.
    _lru.splice(_lru.begin(), _lru, it->second);
    _hit_count.fetch_add(1, std::memory_order_relaxed);
    return it->second->value;
}

void LakeTabletStatCache::insert(int64_t tablet_id, int64_t version, bool accurate, int64_t num_rows,
                                 int64_t data_size, bool count_fast_path_safe) {
    if (!enabled()) {
        return;
    }
    auto key = make_key(tablet_id, version, accurate);
    int64_t expire = now_ms() + ttl_ms();
    std::lock_guard<std::mutex> l(_mutex);
    auto it = _index.find(key);
    if (it != _index.end()) {
        it->second->value = Value{.num_rows = num_rows,
                                  .data_size = data_size,
                                  .count_fast_path_safe = count_fast_path_safe};
        it->second->expire_ms = expire;
        _lru.splice(_lru.begin(), _lru, it->second);
        return;
    }
    _lru.push_front(Entry{.key = key,
                          .value = Value{.num_rows = num_rows,
                                         .data_size = data_size,
                                         .count_fast_path_safe = count_fast_path_safe},
                          .expire_ms = expire});
    _index.emplace(key, _lru.begin());
    evict_to_capacity_locked();
}

void LakeTabletStatCache::evict_to_capacity_locked() {
    const auto cap = static_cast<size_t>(capacity());
    while (_index.size() > cap && !_lru.empty()) {
        auto& victim = _lru.back();
        _index.erase(victim.key);
        _lru.pop_back();
    }
}

void LakeTabletStatCache::clean_expired() {
    int64_t now = now_ms();
    std::lock_guard<std::mutex> l(_mutex);
    for (auto it = _lru.begin(); it != _lru.end();) {
        if (it->expire_ms <= now) {
            _index.erase(it->key);
            it = _lru.erase(it);
        } else {
            ++it;
        }
    }
    // Keep the entry count within the current (possibly lowered) capacity.
    evict_to_capacity_locked();
}

void LakeTabletStatCache::clean_loop() {
    while (!_stopped.load(std::memory_order_relaxed)) {
        int32_t interval_sec = config::lake_tablet_stat_cache_clean_interval_sec;
        if (interval_sec <= 0) {
            interval_sec = 300;
        }
        std::unique_lock<std::mutex> lock(_clean_mutex);
        _clean_cv.wait_for(lock, std::chrono::seconds(interval_sec),
                           [this] { return _stopped.load(std::memory_order_relaxed); });
        if (_stopped.load(std::memory_order_relaxed)) {
            break;
        }
        lock.unlock();
        clean_expired();
    }
}

} // namespace starrocks::lake
