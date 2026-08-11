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

#include "formats/csv/map_converter.h"

#include "column/map_column.h"
#include "common/logging.h"
#include "formats/csv/array_reader.h"

namespace starrocks::csv {

Status MapConverter::write_string(io::FormattedOutputStream* os, const Column& column, size_t row_num,
                                  const Options& options) const {
    auto* map = down_cast<const MapColumn*>(&column);
    auto& offsets = map->offsets();
    auto& keys = map->keys();
    auto& values = map->values();
    const auto offsets_data = offsets.immutable_data();

    auto begin = offsets_data[row_num];
    auto end = offsets_data[row_num + 1];

    RETURN_IF_ERROR(os->write('{'));
    for (auto i = begin; i < end; i++) {
        RETURN_IF_ERROR(_key_converter->write_quoted_string(os, keys, i, options));
        RETURN_IF_ERROR(os->write(_kv_delimiter));
        RETURN_IF_ERROR(_value_converter->write_quoted_string(os, values, i, options));

        if (i + 1 < end) {
            RETURN_IF_ERROR(os->write(_map_delimiter));
        }
    }
    return os->write('}');
}

Status MapConverter::write_quoted_string(io::FormattedOutputStream* os, const Column& column, size_t row_num,
                                         const Options& options) const {
    return write_string(os, column, row_num, options);
}

bool MapConverter::validate(const Slice& s, const Options& options) const {
    if (options.array_format_type == ArrayFormatType::kHive) {
        // Hive text map has no enclosing braces; entries are separated by the collection
        // delimiter and key/value by the mapkey delimiter. An empty slice is a valid empty map.
        return true;
    }
    if (s.size < 2) {
        return false;
    }
    if (s[0] != '{' || s[s.size - 1] != '}') {
        return false;
    }
    return true;
}

bool MapConverter::split_hive_lazy_map(Slice s, char item_separator, char key_value_separator,
                                         std::vector<Slice>& keys, std::vector<Slice>& values) {
    if (s.empty()) {
        return true;
    }

    const size_t array_byte_end = s.size;
    size_t element_byte_begin = 0;
    ssize_t key_value_separator_position = -1;
    size_t element_byte_end = 0;

    // Mirror Hive LazyMap.parse(): scan bytes, split entries by item_separator, and use the
    // first key_value_separator in each entry to split key and value. Values may contain
    // arbitrary characters (including braces/quotes/extra key_value_separator bytes).
    while (element_byte_end <= array_byte_end) {
        if (element_byte_end == array_byte_end || s[element_byte_end] == item_separator) {
            const size_t key_end =
                    (key_value_separator_position == -1) ? element_byte_end : key_value_separator_position;
            if (key_end > element_byte_begin) {
                keys.emplace_back(s.data + element_byte_begin, key_end - element_byte_begin);
                const size_t value_begin =
                        (key_value_separator_position == -1) ? element_byte_end : key_value_separator_position + 1;
                values.emplace_back(s.data + value_begin, element_byte_end - value_begin);
            }

            key_value_separator_position = -1;
            element_byte_begin = element_byte_end + 1;
            element_byte_end++;
        } else {
            if (key_value_separator_position == -1 && s[element_byte_end] == key_value_separator) {
                key_value_separator_position = element_byte_end;
            }
            element_byte_end++;
        }
    }
    return keys.size() == values.size();
}

bool MapConverter::split_map_key_value(Slice s, std::vector<Slice>& keys, std::vector<Slice>& values,
                                        const Options& options) const {
    if (options.array_format_type == ArrayFormatType::kHive) {
        // Hive LazySimpleSerDe map: entries separated by the collection delimiter at this
        // nesting level, key/value by the delimiter one level deeper. No braces to strip.
        size_t level = options.array_hive_nested_level;
        char map_delim = HiveTextArrayReader::get_collection_delimiter(
                options.array_hive_collection_delimiter, options.array_hive_mapkey_delimiter, level);
        char kv_delim = HiveTextArrayReader::get_collection_delimiter(
                options.array_hive_collection_delimiter, options.array_hive_mapkey_delimiter, level + 1);
        return split_hive_lazy_map(s, map_delim, kv_delim, keys, values);
    }

    char map_delim = _map_delimiter;
    char kv_delim = _kv_delimiter;
    s.remove_prefix(1);
    s.remove_suffix(1);
    if (s.empty()) {
        // Consider empty map.
        return true;
    }

    bool in_quote = false;
    int map_nest_level = 0;
    int last_index = 0;
    size_t i = 0;
    for (; i < s.size; i++) {
        char c = s[i];
        if (c == '"') {
            in_quote = !in_quote;
        } else if (!in_quote && c == '{') {
            map_nest_level++;
        } else if (!in_quote && c == '}') {
            map_nest_level--;
        } else if (!in_quote && map_nest_level == 0 && c == kv_delim) {
            if (i == last_index) { // size should not be 0
                return false;
            }
            keys.emplace_back(s.data + last_index, i - last_index);
            last_index = i + 1;
        } else if (!in_quote && map_nest_level == 0 && c == map_delim) {
            if (i == last_index) {
                return false;
            }
            values.emplace_back(s.data + last_index, i - last_index);
            last_index = i + 1;
        }
    }
    if (!in_quote && map_nest_level == 0 && i == s.size) {
        if (i == last_index) {
            return false;
        }
        values.emplace_back(s.data + last_index, i - last_index);
    }
    if (map_nest_level != 0 || in_quote || values.size() != keys.size()) {
        return false;
    }
    return true;
}

bool MapConverter::read_string(Column* column, const Slice& s, const Options& options) const {
    if (!validate(s, options)) {
        return false;
    }
    auto* map = down_cast<MapColumn*>(column);
    auto* offsets = map->offsets_column_raw_ptr();
    auto* keys = map->keys_column_raw_ptr();
    auto* values = map->values_column_raw_ptr();
    std::vector<Slice> key_fields, value_fields;
    if (!s.empty() && !split_map_key_value(s, key_fields, value_fields, options)) {
        return false;
    }
    size_t old_size = keys->size();
    DCHECK_EQ(old_size, offsets->get_data().back());
    DCHECK_EQ(old_size, values->size());

    // get unique keys
    std::vector<bool> unique_keys;
    int unique_num = 0;
    for (auto i = 0; i < key_fields.size(); ++i) {
        bool unique = true;
        for (auto j = i + 1; unique && (j < key_fields.size()); ++j) {
            if (key_fields[i] == key_fields[j]) {
                unique = false;
            }
        }
        unique_num += unique;
        unique_keys.emplace_back(unique);
    }

    // In Hive text format, keys/values are not quoted, so use read_string (mirroring
    // HiveTextArrayReader which calls elem_converter->read_string). A map consumes two
    // separator levels (entry + key/value), so descend two levels for sub-converters.
    const bool is_hive = options.array_format_type == ArrayFormatType::kHive;
    Options sub_options = options;
    if (is_hive) {
        sub_options.array_hive_nested_level = options.array_hive_nested_level + 2;
    }
    for (auto i = 0; i < key_fields.size(); ++i) {
        bool ok = is_hive ? _key_converter->read_string(keys, key_fields[i], sub_options)
                          : _key_converter->read_quoted_string(keys, key_fields[i], options);
        if (unique_keys[i] && !ok) {
            keys->resize(old_size);
            return false;
        }
    }
    for (auto i = 0; i < value_fields.size(); ++i) {
        bool ok = is_hive ? _value_converter->read_string(values, value_fields[i], sub_options)
                          : _value_converter->read_quoted_string(values, value_fields[i], options);
        if (unique_keys[i] && !ok) {
            values->resize(old_size);
            return false;
        }
    }
    offsets->append(old_size + unique_num);
    return true;
}

bool MapConverter::read_quoted_string(Column* column, const Slice& s, const Options& options) const {
    return read_string(column, s, options);
}

} // namespace starrocks::csv
