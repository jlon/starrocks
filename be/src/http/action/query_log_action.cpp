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

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <string>
#include <string_view>
#include <vector>

#include "common/config.h"
#include "http/http_channel.h"
#include "http/http_headers.h"
#include "http/http_request.h"
#include "http/http_status.h"
#include "rapidjson/document.h"
#include "rapidjson/stringbuffer.h"
#include "rapidjson/writer.h"
#include "service/backend_options.h"
#include "util/defer_op.h"

namespace starrocks {
namespace {

constexpr size_t kDefaultPageSize = 100;
constexpr size_t kMaxPageNum = 10000;
constexpr size_t kMaxPageSize = 1000;
constexpr size_t kMaxKeywordLength = 4096;
constexpr std::string_view kCompressedLogSuffix = ".gz";

struct QueryLogFile {
    std::string name;
    uintmax_t size;
};

bool parse_page_param(HttpRequest* req, const std::string& name, size_t default_value, size_t min_value,
                      size_t max_value, size_t* value) {
    const std::string& text = req->param(name);
    if (text.empty()) {
        *value = default_value;
        return true;
    }
    try {
        size_t parsed_length = 0;
        const unsigned long long parsed = std::stoull(text, &parsed_length);
        if (parsed_length != text.size() || parsed < min_value || parsed > max_value) {
            return false;
        }
        *value = static_cast<size_t>(parsed);
        return true;
    } catch (const std::exception&) {
        return false;
    }
}

bool is_query_log_name(const std::string& file_name) {
    const std::string process = BackendOptions::is_cn() ? "cn" : "be";
    const std::string log_name = process + ".INFO.log";
    const std::string rolled_prefix = log_name + ".";
    return (file_name == log_name || (file_name.compare(0, rolled_prefix.size(), rolled_prefix) == 0 &&
                                      file_name.size() > rolled_prefix.size())) &&
           !file_name.ends_with(kCompressedLogSuffix);
}

int open_log_directory(const std::filesystem::path& log_dir) {
    int fd;
    do {
        fd = ::open(log_dir.c_str(), O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    } while (fd < 0 && errno == EINTR);
    return fd;
}

void close_fd(int fd) {
    ::close(fd);
}

bool list_query_logs(std::vector<QueryLogFile>* files, std::string* error) {
    const std::filesystem::path log_dir(config::sys_log_dir);
    const int log_dir_fd = open_log_directory(log_dir);
    if (log_dir_fd < 0) {
        *error = "Log directory does not exist";
        return false;
    }
    DeferOp close_log_dir([&] { close_fd(log_dir_fd); });
    try {
        for (const auto& entry : std::filesystem::directory_iterator(log_dir)) {
            const std::string file_name = entry.path().filename().string();
            if (!is_query_log_name(file_name)) {
                continue;
            }
            struct stat file_stat {};
            if (::fstatat(log_dir_fd, file_name.c_str(), &file_stat, AT_SYMLINK_NOFOLLOW) == 0 &&
                S_ISREG(file_stat.st_mode)) {
                files->push_back({file_name, static_cast<uintmax_t>(file_stat.st_size)});
            }
        }
        std::sort(files->begin(), files->end(),
                  [](const QueryLogFile& left, const QueryLogFile& right) { return left.name > right.name; });
        return true;
    } catch (const std::filesystem::filesystem_error&) {
        *error = "Failed to list query logs";
        return false;
    }
}

FILE* open_query_log(const std::filesystem::path& log_dir, const std::string& file_name) {
    const int log_dir_fd = open_log_directory(log_dir);
    if (log_dir_fd < 0) {
        return nullptr;
    }
    DeferOp close_log_dir([&] { close_fd(log_dir_fd); });

    int file_fd;
    do {
        file_fd = ::openat(log_dir_fd, file_name.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    } while (file_fd < 0 && errno == EINTR);
    if (file_fd < 0) {
        return nullptr;
    }

    struct stat file_stat {};
    if (::fstat(file_fd, &file_stat) != 0 || !S_ISREG(file_stat.st_mode)) {
        close_fd(file_fd);
        return nullptr;
    }

    FILE* input = ::fdopen(file_fd, "r");
    if (input == nullptr) {
        close_fd(file_fd);
    }
    return input;
}

void send_json(HttpRequest* req, HttpStatus status, rapidjson::Document* document) {
    rapidjson::StringBuffer buffer;
    rapidjson::Writer<rapidjson::StringBuffer> writer(buffer);
    document->Accept(writer);
    req->add_output_header(HttpHeaders::CONTENT_TYPE, "application/json");
    HttpChannel::send_reply(req, status, buffer.GetString());
}

void send_error(HttpRequest* req, HttpStatus status, const char* message) {
    rapidjson::Document root;
    root.SetObject();
    auto& allocator = root.GetAllocator();
    root.AddMember("success", false, allocator);
    root.AddMember("errorMessage", rapidjson::Value(message, allocator), allocator);
    send_json(req, status, &root);
}

} // namespace

void QueryLogAction::handle(HttpRequest* req) {
    if (req->method() != HttpMethod::GET) {
        send_error(req, HttpStatus::METHOD_NOT_ALLOWED, "Method Not Allowed");
        return;
    }

    std::vector<QueryLogFile> files;
    std::string error;
    if (!list_query_logs(&files, &error)) {
        send_error(req, HttpStatus::INTERNAL_SERVER_ERROR, error.c_str());
        return;
    }

    if (_type == QueryLogActionType::LIST) {
        rapidjson::Document root;
        root.SetObject();
        auto& allocator = root.GetAllocator();
        rapidjson::Value logs(rapidjson::kArrayType);
        for (const auto& file : files) {
            rapidjson::Value log(rapidjson::kObjectType);
            log.AddMember("name", rapidjson::Value(file.name.c_str(), allocator), allocator);
            log.AddMember("size", static_cast<uint64_t>(file.size), allocator);
            logs.PushBack(log, allocator);
        }
        root.AddMember("logs", logs, allocator);
        send_json(req, HttpStatus::OK, &root);
        return;
    }

    const std::string& file_name = req->param("file");
    const std::string& keyword = req->param("keyword");
    if (file_name.empty() || keyword.empty()) {
        send_error(req, HttpStatus::BAD_REQUEST, "Missing file or keyword parameter");
        return;
    }
    if (keyword.size() > kMaxKeywordLength) {
        send_error(req, HttpStatus::BAD_REQUEST, "Keyword parameter is too long");
        return;
    }

    size_t page_num = 0;
    size_t page_size = 0;
    if (!parse_page_param(req, "page_num", 0, 0, kMaxPageNum, &page_num) ||
        !parse_page_param(req, "page_size", kDefaultPageSize, 1, kMaxPageSize, &page_size)) {
        send_error(req, HttpStatus::BAD_REQUEST, "Invalid page_num or page_size parameter");
        return;
    }

    const auto file = std::find_if(files.begin(), files.end(),
                                   [&file_name](const QueryLogFile& candidate) { return candidate.name == file_name; });
    if (file == files.end()) {
        send_error(req, HttpStatus::NOT_FOUND, "Query log file not found");
        return;
    }

    const auto started_at = std::chrono::steady_clock::now();
    FILE* input = open_query_log(std::filesystem::path(config::sys_log_dir), file->name);
    if (input == nullptr) {
        send_error(req, HttpStatus::NOT_FOUND, "Query log file not found");
        return;
    }
    DeferOp close_input([&] { ::fclose(input); });

    const size_t skipped_matches = page_num * page_size;
    size_t matched = 0;
    size_t line_number = 0;
    bool has_more = false;
    rapidjson::Document root;
    root.SetObject();
    auto& allocator = root.GetAllocator();
    rapidjson::Value results(rapidjson::kArrayType);
    char* line_buffer = nullptr;
    size_t line_capacity = 0;
    DeferOp free_line_buffer([&] { ::free(line_buffer); });
    ssize_t line_length;
    while ((line_length = ::getline(&line_buffer, &line_capacity, input)) != -1) {
        ++line_number;
        if (line_length > 0 && line_buffer[line_length - 1] == '\n') {
            --line_length;
        }
        if (line_length > 0 && line_buffer[line_length - 1] == '\r') {
            --line_length;
        }
        const std::string_view line(line_buffer, line_length);
        if (line.find(std::string_view(keyword)) == std::string_view::npos) {
            continue;
        }
        if (matched++ < skipped_matches) {
            continue;
        }
        if (results.Size() == page_size) {
            has_more = true;
            break;
        }
        rapidjson::Value result(rapidjson::kObjectType);
        result.AddMember("lineNumber", static_cast<uint64_t>(line_number), allocator);
        result.AddMember("matchedLine",
                         rapidjson::Value(line.data(), static_cast<rapidjson::SizeType>(line.size()), allocator),
                         allocator);
        result.AddMember("isMatch", true, allocator);
        results.PushBack(result, allocator);
    }

    root.AddMember("success", true, allocator);
    root.AddMember("results", results, allocator);
    root.AddMember("pageNum", static_cast<uint64_t>(page_num), allocator);
    root.AddMember("pageSize", static_cast<uint64_t>(page_size), allocator);
    root.AddMember("hasMore", has_more, allocator);
    const auto elapsed =
            std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started_at);
    root.AddMember("executionTimeMs", elapsed.count(), allocator);
    send_json(req, HttpStatus::OK, &root);
}

} // namespace starrocks
