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

#include "storage/lake/filenames.h"

#include <gtest/gtest.h>

#include "gutil/strings/util.h"
#include "util/string_parser.hpp"

namespace starrocks::lake {

class FilenamesTest : public testing::Test {
public:
    FilenamesTest() = default;
    ~FilenamesTest() override = default;
};

TEST_F(FilenamesTest, extract_uuid_from) {
    // Test valid segment file names
    {
        std::string file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.dat";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_EQ("6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b", uuid);
    }

    // Test valid del file names
    {
        std::string file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.del";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_EQ("6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b", uuid);
    }

    // Test valid sst file names
    {
        std::string file_name = "6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.sst";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_EQ("6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b", uuid);
    }

    // Test valid delvec file names
    {
        std::string file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.delvec";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_EQ("6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b", uuid);
    }

    // Test valid cols file names
    {
        std::string file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.cols";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_EQ("6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b", uuid);
    }

    // Test invalid file names - wrong extension
    {
        std::string file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.txt";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_TRUE(uuid.empty());
    }

    // Test invalid file names - wrong position of underscore
    {
        std::string file_name = "00000001_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.dat";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_TRUE(uuid.empty());
    }

    // Test invalid file names - too short
    {
        std::string file_name = "6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.dat";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_TRUE(uuid.empty());
    }

    // Test invalid file names - empty string
    {
        std::string file_name = "";
        std::string uuid = extract_uuid_from(file_name);
        ASSERT_TRUE(uuid.empty());
    }
}

TEST_F(FilenamesTest, gen_segment_filename_from) {
    int64_t new_txn_id = 4;

    // Test valid segment file generation
    {
        std::string old_file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.dat";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        ASSERT_EQ("0000000000000004_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.dat", new_file_name);
    }

    // Test valid del file input
    {
        std::string old_file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.del";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        ASSERT_EQ("0000000000000004_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.del", new_file_name);
    }

    // Test valid sst file input (sst file only has uuid as name, no txn id)
    {
        std::string old_file_name = "6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.sst";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        // file name never changed
        ASSERT_EQ(old_file_name, new_file_name);
    }

    // Test valid delvec file input
    {
        std::string old_file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.delvec";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        ASSERT_EQ("0000000000000004_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.delvec", new_file_name);
    }

    // Test valid cols file input
    {
        std::string old_file_name = "0000000000000003_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.cols";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        ASSERT_EQ("0000000000000004_6bc1edf0-fba6-4aa1-b0d4-ee5b88ef156b.cols", new_file_name);
    }

    // Test invalid old file name
    {
        std::string old_file_name = "invalid_filename.txt";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        ASSERT_TRUE(new_file_name.empty());
    }

    // Test empty old file name
    {
        std::string old_file_name = "";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        ASSERT_TRUE(new_file_name.empty());
    }

    // Test file name with correct UUID but wrong extension
    {
        std::string old_file_name = "0000000000000123_abcdef1234567890.log";
        std::string new_file_name = gen_filename_from(new_txn_id, old_file_name);
        ASSERT_TRUE(new_file_name.empty());
    }
}

TEST_F(FilenamesTest, try_parse_txn_log_filename) {
    const auto name = txn_log_filename(0x445C0, 0xC02);
    auto parsed = try_parse_txn_log_filename(name);
    ASSERT_TRUE(parsed.has_value());
    EXPECT_EQ(0x445C0, parsed->first);
    EXPECT_EQ(0xC02, parsed->second);

    const std::string listed_path = "/home/service/var/openclaw/test/log/" + name;
    auto listed_name = basename(listed_path);
    EXPECT_EQ(name, listed_name);
    parsed = try_parse_txn_log_filename(listed_name);
    ASSERT_TRUE(parsed.has_value());
    EXPECT_EQ(0x445C0, parsed->first);
    EXPECT_EQ(0xC02, parsed->second);

    parsed = try_parse_txn_log_filename("0000000000000001_0000000000000002_FFFFFFFFFFFFFFFF_8000000000000000.log");
    ASSERT_TRUE(parsed.has_value());
    EXPECT_EQ(1, parsed->first);
    EXPECT_EQ(2, parsed->second);

    EXPECT_FALSE(try_parse_txn_log_filename(listed_path).has_value());
    EXPECT_FALSE(try_parse_txn_log_filename("xxxx_xxxx.log").has_value());
    EXPECT_FALSE(try_parse_txn_log_filename("0000000000000001_0000000000000002_bad_load_id.log").has_value());
}

TEST_F(FilenamesTest, try_parse_tablet_metadata_filename) {
    const auto name = tablet_metadata_filename(0x445C0, 0xC02);
    auto parsed = try_parse_tablet_metadata_filename(name);
    ASSERT_TRUE(parsed.has_value());
    EXPECT_EQ(0x445C0, parsed->first);
    EXPECT_EQ(0xC02, parsed->second);

    const std::string listed_path = "/business/path/meta/" + name;
    EXPECT_FALSE(try_parse_tablet_metadata_filename(listed_path).has_value());
    parsed = try_parse_tablet_metadata_filename(basename(listed_path));
    ASSERT_TRUE(parsed.has_value());
    EXPECT_EQ(0x445C0, parsed->first);
    EXPECT_EQ(0xC02, parsed->second);

    EXPECT_FALSE(try_parse_tablet_metadata_filename("xxxx_xxxx.meta").has_value());
    EXPECT_FALSE(try_parse_tablet_metadata_filename("00000000000445C0_xxxxxxxxxxxxxxxx.meta").has_value());
    EXPECT_FALSE(try_parse_tablet_metadata_filename("00000000000445C0000000000000C02.meta").has_value());
    EXPECT_FALSE(try_parse_tablet_metadata_filename("00000000000445C0_0000000000000C02.log").has_value());
}

TEST_F(FilenamesTest, try_parse_txn_slog_vlog_and_combined_log_filename) {
    {
        auto parsed = try_parse_txn_slog_filename(txn_slog_filename(0x445C0, 0xC02));
        ASSERT_TRUE(parsed.has_value());
        EXPECT_EQ(0x445C0, parsed->first);
        EXPECT_EQ(0xC02, parsed->second);
        EXPECT_FALSE(try_parse_txn_slog_filename("00000000000445C0_xxxxxxxxxxxxxxxx.slog").has_value());
    }
    {
        auto parsed = try_parse_txn_vlog_filename(txn_vlog_filename(0x445C0, 0xC02));
        ASSERT_TRUE(parsed.has_value());
        EXPECT_EQ(0x445C0, parsed->first);
        EXPECT_EQ(0xC02, parsed->second);
        EXPECT_FALSE(try_parse_txn_vlog_filename("00000000000445C0_xxxxxxxxxxxxxxxx.vlog").has_value());
    }
    {
        auto parsed = try_parse_combined_txn_log_filename(combined_txn_log_filename(0xC02));
        ASSERT_TRUE(parsed.has_value());
        EXPECT_EQ(0xC02, *parsed);
        EXPECT_FALSE(try_parse_combined_txn_log_filename("xxxx.logs").has_value());
    }
}
} // namespace starrocks::lake
