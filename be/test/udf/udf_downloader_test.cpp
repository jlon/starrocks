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

#include "udf/udf_downloader.h"

#include <filesystem>

#include <fmt/format.h>
#include <gtest/gtest.h>

#include "fs/fs.h"
#include "fs/fs_util.h"
#include "testutil/assert.h"

namespace starrocks {

class UdfDownloaderTest : public testing::Test {
public:
    void SetUp() override {
        _test_dir = fmt::format("{}/{}", std::filesystem::current_path().string(), kTestDirectory);
        CHECK_OK(fs::remove_all(_test_dir));
        CHECK_OK(fs::create_directories(_test_dir));

        _source_file = fmt::format("{}/source.jar", _test_dir);
        auto file = fopen(_source_file.c_str(), "wb");
        ASSERT_NE(file, nullptr);
        constexpr char kData[] = "oppo-hive-udf";
        ASSERT_EQ(sizeof(kData) - 1, fwrite(kData, 1, sizeof(kData) - 1, file));
        ASSERT_EQ(0, fclose(file));
        _source_md5 = fs::md5sum(_source_file).value();
    }

    void TearDown() override { ASSERT_OK(fs::remove_all(_test_dir)); }

protected:
    std::string destination() const { return fmt::format("{}/destination.jar", _test_dir); }

    constexpr static const char* kTestDirectory = "test_udf_downloader";
    std::string _test_dir;
    std::string _source_file;
    std::string _source_md5;
    udf_downloader _downloader;
};

TEST_F(UdfDownloaderTest, downloadsAbsoluteLocalFile) {
    std::string destination_file = destination();
    ASSERT_OK(_downloader.do_download(destination_file, fmt::format("file://{}", _source_file), _source_md5,
                                      FSOptions{}));
    ASSERT_TRUE(fs::path_exist(destination_file));
    ASSERT_EQ(_source_md5, fs::md5sum(destination_file).value());
}

TEST_F(UdfDownloaderTest, removesDestinationWhenChecksumDoesNotMatch) {
    std::string destination_file = destination();
    ASSERT_ERROR(_downloader.do_download(destination_file, fmt::format("file://{}", _source_file), "invalid",
                                         FSOptions{}));
    ASSERT_FALSE(fs::path_exist(destination_file));
}

TEST_F(UdfDownloaderTest, rejectsRelativeLocalFile) {
    std::string destination_file = destination();
    ASSERT_ERROR(_downloader.do_download(destination_file, "file://relative.jar", _source_md5, FSOptions{}));
    ASSERT_FALSE(fs::path_exist(destination_file));
}

} // namespace starrocks
