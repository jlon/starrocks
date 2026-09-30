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

#include "http/action/query_log_action.h"

#include <event2/http.h>
#include <event2/http_struct.h>
#include <gtest/gtest.h>
#include <rapidjson/document.h>

#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "common/config.h"
#include "http/http_channel.h"
#include "http/http_request.h"
#include "service/backend_options.h"

namespace starrocks {

extern void (*s_injected_send_reply)(HttpRequest*, HttpStatus, std::string_view);

namespace {

std::string response_body;
HttpStatus response_status = HttpStatus::INTERNAL_SERVER_ERROR;

void inject_send_reply(HttpRequest*, HttpStatus status, std::string_view content) {
    response_status = status;
    response_body.assign(content);
}

} // namespace

class QueryLogActionTest : public testing::Test {
public:
    static void SetUpTestSuite() { s_injected_send_reply = inject_send_reply; }
    static void TearDownTestSuite() { s_injected_send_reply = nullptr; }

    void SetUp() override {
        test_dir = std::filesystem::temp_directory_path() / "starrocks_query_log_action_test";
        std::filesystem::remove_all(test_dir);
        std::filesystem::create_directories(test_dir);
        original_sys_log_dir = config::sys_log_dir;
        config::sys_log_dir = test_dir.string();
        log_name = std::string(BackendOptions::is_cn() ? "cn" : "be") + ".INFO.log";
        rolled_log_name = log_name + ".20260301-000000";
        write_file(log_name, "first line\nneedle.one\nneedle.two\n");
        write_file(rolled_log_name, "rolled query log\n");
        write_file(rolled_log_name + ".gz", "compressed query log\n");
        write_file(std::string(BackendOptions::is_cn() ? "cn" : "be") + ".WARNING.log.20260301-000000",
                   "not a query log\n");
        std::filesystem::create_symlink(test_dir / log_name, test_dir / (log_name + ".link"));
        response_body.clear();
    }

    void TearDown() override {
        for (auto* request : ev_requests) {
            evhttp_request_free(request);
        }
        config::sys_log_dir = original_sys_log_dir;
        std::filesystem::remove_all(test_dir);
    }

    std::unique_ptr<HttpRequest> create_request(QueryLogAction* action) {
        auto* ev_request = evhttp_request_new(nullptr, nullptr);
        ev_requests.push_back(ev_request);
        auto request = std::make_unique<HttpRequest>(ev_request);
        request->set_method(HttpMethod::GET);
        request->set_handler(action);
        return request;
    }

    void write_file(const std::string& name, const std::string& content) {
        std::ofstream output(test_dir / name);
        output << content;
    }

protected:
    std::filesystem::path test_dir;
    std::string original_sys_log_dir;
    std::string log_name;
    std::string rolled_log_name;
    std::vector<evhttp_request*> ev_requests;
};

TEST_F(QueryLogActionTest, lists_only_info_logs) {
    QueryLogAction action(QueryLogActionType::LIST);
    auto request = create_request(&action);

    action.handle(request.get());

    ASSERT_EQ(HttpStatus::OK, response_status);
    rapidjson::Document document;
    document.Parse(response_body.c_str());
    ASSERT_TRUE(document.IsObject());
    ASSERT_EQ(2, document["logs"].Size());
    EXPECT_EQ(rolled_log_name, document["logs"][0]["name"].GetString());
    EXPECT_EQ(log_name, document["logs"][1]["name"].GetString());
    EXPECT_GT(document["logs"][0]["size"].GetUint64(), 0);
}

TEST_F(QueryLogActionTest, searches_literal_keyword_with_pagination) {
    QueryLogAction action(QueryLogActionType::SEARCH);
    auto request = create_request(&action);
    request->add_param("file", log_name);
    request->add_param("keyword", "needle.");
    request->add_param("page_size", "1");

    action.handle(request.get());

    ASSERT_EQ(HttpStatus::OK, response_status);
    rapidjson::Document document;
    document.Parse(response_body.c_str());
    ASSERT_TRUE(document["success"].GetBool());
    ASSERT_EQ(1, document["results"].Size());
    EXPECT_EQ(2, document["results"][0]["lineNumber"].GetUint64());
    EXPECT_STREQ("needle.one", document["results"][0]["matchedLine"].GetString());
    EXPECT_TRUE(document["hasMore"].GetBool());

    request = create_request(&action);
    request->add_param("file", log_name);
    request->add_param("keyword", "needle.");
    request->add_param("page_num", "1");
    request->add_param("page_size", "1");
    action.handle(request.get());

    document.Parse(response_body.c_str());
    ASSERT_EQ(1, document["results"].Size());
    EXPECT_EQ(3, document["results"][0]["lineNumber"].GetUint64());
    EXPECT_FALSE(document["hasMore"].GetBool());
}

TEST_F(QueryLogActionTest, rejects_unlisted_files) {
    QueryLogAction action(QueryLogActionType::SEARCH);
    auto request = create_request(&action);
    request->add_param("file", "../../etc/passwd");
    request->add_param("keyword", "root");

    action.handle(request.get());

    ASSERT_EQ(HttpStatus::NOT_FOUND, response_status);
    rapidjson::Document document;
    document.Parse(response_body.c_str());
    EXPECT_FALSE(document["success"].GetBool());

    request = create_request(&action);
    request->add_param("file", log_name);
    request->add_param("keyword", "needle");
    request->add_param("page_size", "0");
    action.handle(request.get());
    EXPECT_EQ(HttpStatus::BAD_REQUEST, response_status);
}

} // namespace starrocks
