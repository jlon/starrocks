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

#include "storage/chunk_iterator.h"

#include <algorithm>
#include <atomic>
#include <future>
#include <mutex>

#include "runtime/exec_env.h"
#include "util/defer_op.h"

namespace starrocks {

Status prepare_chunk_iterator(const ChunkIteratorPtr& child) {
    if (child == nullptr) {
        return Status::OK();
    }
    auto* prepared = dynamic_cast<PreparedChunkIterator*>(child.get());
    if (prepared == nullptr) {
        return Status::OK();
    }
    return prepared->prepare();
}

Status prepare_chunk_iterators(const std::vector<ChunkIteratorPtr>& children, size_t start) {
    if (start >= children.size()) {
        return Status::OK();
    }
    if (children.size() - start == 1) {
        return prepare_chunk_iterator(children[start]);
    }

    constexpr size_t kMaxParallelPrepare = 8;
    const size_t concurrency = std::min(kMaxParallelPrepare, children.size() - start);
    std::atomic<size_t> next{start};
    std::atomic<bool> failed{false};
    std::mutex status_mutex;
    Status first_error;

    auto worker = [&]() {
        while (true) {
            if (failed.load(std::memory_order_acquire)) {
                return;
            }
            const size_t idx = next.fetch_add(1);
            if (idx >= children.size()) {
                return;
            }
            auto st = prepare_chunk_iterator(children[idx]);
            if (!st.ok()) {
                std::lock_guard guard(status_mutex);
                if (first_error.ok()) {
                    first_error = st;
                    failed.store(true, std::memory_order_release);
                }
                return;
            }
        }
    };

    std::vector<std::future<void>> futures;
    futures.reserve(concurrency);
    // Workers capture the locals above by reference. Guarantee every submitted future is waited on
    // before this frame returns, even if a worker throws, so no pool thread outlives these locals.
    DeferOp wait_remaining([&futures]() {
        for (auto& future : futures) {
            if (future.valid()) {
                future.wait();
            }
        }
    });

    auto* thread_pool = ExecEnv::GetInstance()->load_segment_thread_pool();
    if (thread_pool == nullptr) {
        for (size_t i = 0; i < concurrency; ++i) {
            futures.emplace_back(std::async(std::launch::async, worker));
        }
    } else {
        for (size_t i = 0; i < concurrency; ++i) {
            auto task = std::make_shared<std::packaged_task<void()>>(worker);
            auto st = thread_pool->submit_func([task]() { (*task)(); });
            if (!st.ok()) {
                worker();
                break;
            }
            futures.emplace_back(task->get_future());
        }
    }

    for (auto& future : futures) {
        future.get();
    }
    return first_error;
}

class TimedChunkIterator final : public ChunkIterator, public PreparedChunkIterator {
public:
    TimedChunkIterator(ChunkIteratorPtr iter, RuntimeProfile::Counter* counter)
            : ChunkIterator(iter->schema(), iter->chunk_size()), _iter(std::move(iter)), _counter(counter) {}

    ~TimedChunkIterator() override = default;

    void close() override {
        COUNTER_UPDATE(_counter, _cost);
        _iter->close();
        _iter.reset();
    }

    size_t merged_rows() const override { return _iter->merged_rows(); }

    Status prepare() override {
        SCOPED_RAW_TIMER(&_cost);
        return prepare_chunk_iterator(_iter);
    }

    Status init_encoded_schema(ColumnIdToGlobalDictMap& dict_maps) override {
        RETURN_IF_ERROR(ChunkIterator::init_encoded_schema(dict_maps));
        RETURN_IF_ERROR(_iter->init_encoded_schema(dict_maps));
        return Status::OK();
    }

    Status init_output_schema(const std::unordered_set<uint32_t>& unused_output_column_ids) override {
        RETURN_IF_ERROR(ChunkIterator::init_output_schema(unused_output_column_ids));
        RETURN_IF_ERROR(_iter->init_output_schema(unused_output_column_ids));
        return Status::OK();
    }

private:
    Status do_get_next(Chunk* chunk) override {
        SCOPED_RAW_TIMER(&_cost);
        return _iter->get_next(chunk);
    }

    Status do_get_next(Chunk* chunk, std::vector<uint32_t>* rowid) override {
        SCOPED_RAW_TIMER(&_cost);
        return _iter->get_next(chunk, rowid);
    }

    Status do_get_next(Chunk* chunk, std::vector<uint64_t>* rssid_rowids) override {
        SCOPED_RAW_TIMER(&_cost);
        return _iter->get_next(chunk, rssid_rowids);
    }

    Status do_get_next(Chunk* chunk, std::vector<RowSourceMask>* source_masks) override {
        SCOPED_RAW_TIMER(&_cost);
        return _iter->get_next(chunk, source_masks);
    }

    Status do_get_next(Chunk* chunk, std::vector<RowSourceMask>* source_masks,
                       std::vector<uint64_t>* rssid_rowids) override {
        SCOPED_RAW_TIMER(&_cost);
        return _iter->get_next(chunk, source_masks, rssid_rowids);
    }

    ChunkIteratorPtr _iter;
    int64_t _cost{0};
    RuntimeProfile::Counter* _counter;
};

ChunkIteratorPtr timed_chunk_iterator(const ChunkIteratorPtr& iter, RuntimeProfile::Counter* counter) {
    return std::make_shared<TimedChunkIterator>(iter, counter);
}

} // namespace starrocks
