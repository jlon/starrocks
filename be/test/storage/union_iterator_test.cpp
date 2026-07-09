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

#include "storage/union_iterator.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <memory>
#include <thread>
#include <vector>

#include "column/chunk.h"
#include "column/fixed_length_column.h"
#include "column/schema.h"
#include "common/config.h"
#include "storage/chunk_helper.h"
#include "testutil/assert.h"

namespace starrocks {

class UnionIteratorTest : public testing::Test {
protected:
    void SetUp() override {}
    void TearDown() override {}

    // return chunk with single column of type int32_t.
    class IntIterator final : public ChunkIterator {
    public:
        explicit IntIterator(std::vector<int32_t> numbers) : ChunkIterator(schema()), _numbers(std::move(numbers)) {}

        // 10 elements at most every time.
        Status do_get_next(Chunk* chunk) override {
            if (_idx >= _numbers.size()) {
                return Status::EndOfFile("eof");
            }
            size_t n = std::min(10LU, _numbers.size() - _idx);
            auto* c = chunk->get_column_raw_ptr_by_index(0);
            (void)c->append_numbers(_numbers.data() + _idx, n * sizeof(int32_t));
            _idx += n;
            return Status::OK();
        }

        // 10 elements at most every time. And also return rssid rowids
        Status do_get_next(Chunk* chunk, std::vector<uint64_t>* rssid_rowids) override {
            if (_idx >= _numbers.size()) {
                return Status::EndOfFile("eof");
            }
            size_t n = std::min(10LU, _numbers.size() - _idx);
            auto* c = chunk->get_column_raw_ptr_by_index(0);
            (void)c->append_numbers(_numbers.data() + _idx, n * sizeof(int32_t));
            _idx += n;
            for (size_t i = 0; i < n; i++) {
                rssid_rowids->push_back(i);
            }
            return Status::OK();
        }

        void close() override {}

        static Schema schema() {
            FieldPtr f = std::make_shared<Field>(0, "c1", get_type_info(TYPE_INT), false);
            return Schema(std::vector<FieldPtr>{f});
        }

    private:
        size_t _idx = 0;
        std::vector<int32_t> _numbers;
    };

    class PrepareTrackingIterator final : public ChunkIterator, public PreparedChunkIterator {
    public:
        PrepareTrackingIterator(std::vector<int32_t> numbers, std::atomic<int>* active, std::atomic<int>* max_active,
                                std::atomic<int>* prepare_count)
                : ChunkIterator(IntIterator::schema()),
                  _numbers(std::move(numbers)),
                  _active(active),
                  _max_active(max_active),
                  _prepare_count(prepare_count) {}

        Status prepare() override {
            _prepare_count->fetch_add(1);
            int active = _active->fetch_add(1) + 1;
            int old_max = _max_active->load();
            while (active > old_max && !_max_active->compare_exchange_weak(old_max, active)) {
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            _active->fetch_sub(1);
            return Status::OK();
        }

        Status do_get_next(Chunk* chunk) override {
            if (_idx >= _numbers.size()) {
                return Status::EndOfFile("eof");
            }
            size_t n = std::min(10LU, _numbers.size() - _idx);
            auto* c = chunk->get_column_raw_ptr_by_index(0);
            (void)c->append_numbers(_numbers.data() + _idx, n * sizeof(int32_t));
            _idx += n;
            return Status::OK();
        }

        void close() override {}

    private:
        size_t _idx = 0;
        std::vector<int32_t> _numbers;
        std::atomic<int>* _active;
        std::atomic<int>* _max_active;
        std::atomic<int>* _prepare_count;
    };
};

// NOLINTNEXTLINE
TEST_F(UnionIteratorTest, union_two) {
    config::vector_chunk_size = 1024;
    std::vector<int32_t> n1{6, 7, 8};
    std::vector<int32_t> n2{1, 2, 3, 4, 5};
    auto sub1 = std::make_shared<IntIterator>(n1);
    auto sub2 = std::make_shared<IntIterator>(n2);

    auto iter = new_union_iterator({sub1, sub2});

    auto get_row = [](const ChunkPtr& chunk, size_t row) -> int32_t {
        auto c = FixedLengthColumn<int32_t>::dynamic_pointer_cast(chunk->get_column_by_index(0));
        return c->immutable_data()[row];
    };

    ChunkPtr chunk = ChunkHelper::new_chunk(iter->schema(), config::vector_chunk_size);
    ASSERT_TRUE(iter->init_encoded_schema(EMPTY_GLOBAL_DICTMAPS).ok());

    Status st = iter->get_next(chunk.get());
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(3U, chunk->num_rows());
    ASSERT_EQ(6, get_row(chunk, 0));
    ASSERT_EQ(7, get_row(chunk, 1));
    ASSERT_EQ(8, get_row(chunk, 2));

    chunk->reset();
    std::vector<uint64_t> rssid_rowids;
    st = iter->get_next(chunk.get(), nullptr, &rssid_rowids);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(5U, chunk->num_rows());
    ASSERT_EQ(5U, rssid_rowids.size());
    ASSERT_EQ(1, get_row(chunk, 0));
    ASSERT_EQ(2, get_row(chunk, 1));
    ASSERT_EQ(3, get_row(chunk, 2));
    ASSERT_EQ(4, get_row(chunk, 3));
    ASSERT_EQ(5, get_row(chunk, 4));
    ASSERT_EQ(0, rssid_rowids[0]);
    ASSERT_EQ(1, rssid_rowids[1]);
    ASSERT_EQ(2, rssid_rowids[2]);
    ASSERT_EQ(3, rssid_rowids[3]);
    ASSERT_EQ(4, rssid_rowids[4]);

    chunk->reset();
    st = iter->get_next(chunk.get());
    ASSERT_TRUE(st.is_end_of_file());

    std::vector<RowSourceMask> source_masks;
    st = iter->get_next(chunk.get(), &source_masks, &rssid_rowids);
    ASSERT_TRUE(st.is_not_supported());
}

// NOLINTNEXTLINE
TEST_F(UnionIteratorTest, union_one) {
    config::vector_chunk_size = 1024;
    std::vector<int32_t> n1{1, 2, 3, 4, 5};
    auto sub1 = std::make_shared<IntIterator>(n1);

    auto iter = new_union_iterator({sub1});
    ASSERT_TRUE(iter->init_encoded_schema(EMPTY_GLOBAL_DICTMAPS).ok());

    auto get_row = [](const ChunkPtr& chunk, size_t row) -> int32_t {
        auto c = FixedLengthColumn<int32_t>::dynamic_pointer_cast(chunk->get_column_by_index(0));
        return c->immutable_data()[row];
    };

    ChunkPtr chunk = ChunkHelper::new_chunk(iter->schema(), config::vector_chunk_size);
    Status st = iter->get_next(chunk.get());
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(5U, chunk->num_rows());
    ASSERT_EQ(1, get_row(chunk, 0));
    ASSERT_EQ(2, get_row(chunk, 1));
    ASSERT_EQ(3, get_row(chunk, 2));
    ASSERT_EQ(4, get_row(chunk, 3));
    ASSERT_EQ(5, get_row(chunk, 4));

    chunk->reset();
    st = iter->get_next(chunk.get());
    ASSERT_TRUE(st.is_end_of_file());
}

TEST_F(UnionIteratorTest, prepare_children_in_parallel) {
    std::atomic<int> active{0};
    std::atomic<int> max_active{0};
    std::atomic<int> prepare_count{0};
    auto sub1 = std::make_shared<PrepareTrackingIterator>(std::vector<int32_t>{1}, &active, &max_active, &prepare_count);
    auto sub2 = std::make_shared<PrepareTrackingIterator>(std::vector<int32_t>{2}, &active, &max_active, &prepare_count);

    auto iter = new_union_iterator({sub1, sub2});
    ASSERT_OK(prepare_chunk_iterator(iter));

    EXPECT_EQ(2, prepare_count.load());
    EXPECT_GE(max_active.load(), 2);
}

} // namespace starrocks
