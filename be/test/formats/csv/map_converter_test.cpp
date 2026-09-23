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

#include <gtest/gtest.h>

#include "column/column_helper.h"
#include "formats/csv/converter.h"
#include "io/formatted_output_stream_string.h"
#include "runtime/types.h"

namespace starrocks::csv {

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_write_map_int_string) {
    // map<TINYINT,VARCHAR>
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_TINYINT);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 100;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);
    // read
    EXPECT_TRUE(conv->read_string(col.get(), "{}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:\"abc\",2:NULL,3:\"\"}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{NULL:NULL}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:\"abc\",2:NULL,1:NULL}", Converter::Options())); // duplicated keys

    EXPECT_EQ(4, col->size());
    // {}
    EXPECT_EQ(0, col->get(0).get_map().size());
    EXPECT_EQ("{}", col->debug_item(0));
    // {1:abc,2:NULL,3:""}
    EXPECT_EQ(3, col->get(1).get_map().size());
    EXPECT_EQ("{1:'abc',2:NULL,3:''}", col->debug_item(1));
    // {NULL:NULL}
    EXPECT_EQ("{NULL:NULL}", col->debug_item(2));
    // {1:NULL,2:NULL}
    EXPECT_EQ(2, col->get(3).get_map().size());
    EXPECT_EQ("{2:NULL,1:NULL}", col->debug_item(3));

    // write
    io::FormattedOutputStreamString buff;
    ASSERT_TRUE(conv->write_string(&buff, *col, 0, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 1, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 2, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 3, Converter::Options()).ok());
    ASSERT_TRUE(buff.finalize().ok());
    ASSERT_EQ("{}{1:\"abc\",2:null,3:\"\"}{null:null}{2:null,1:null}", buff.as_string());
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_write_nest_map) {
    // map<int,map<TINYINT,VARCHAR>>
    TypeDescriptor n(TYPE_MAP);
    n.children.emplace_back(TYPE_TINYINT);
    n.children.emplace_back(TYPE_VARCHAR);
    n.children.back().len = 100;
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_INT);
    t.children.push_back(n);

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    EXPECT_TRUE(conv->read_string(col.get(), "{}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:{}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{1:{1:\"abc\",2:NULL},2:{3:\"\"}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{NULL:{NULL:NULL}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{NULL:{NULL:\"NULL\"},-20308764:{33:\"\"}}", Converter::Options()));
    EXPECT_TRUE(conv->read_string(col.get(), "{11:{1:\"abc\",2:NULL,1:NULL},22:{NULL:NULL},22:{2:\"unique\"}}",
                                  Converter::Options())); // duplicated keys

    EXPECT_EQ(6, col->size());
    // {}
    EXPECT_EQ(0, col->get(0).get_map().size());
    // {1:{}}
    EXPECT_EQ(1, col->get(1).get_map().size());
    EXPECT_EQ("{1:{}}", col->debug_item(1));
    // {1:{1:"abc",2:NULL},2:{3:""}}
    EXPECT_EQ(2, col->get(2).get_map().size());
    EXPECT_EQ("{1:{1:'abc',2:NULL},2:{3:''}}", col->debug_item(2));
    // {NULL:{NULL:NULL}}
    EXPECT_EQ("{NULL:{NULL:NULL}}", col->debug_item(3));
    // {NULL:{NULL:"NULL"},-20308764:{33:""}}
    EXPECT_EQ("{NULL:{NULL:'NULL'},-20308764:{33:''}}", col->debug_item(4));
    // {11:{1:"abc",2:NULL,1:NULL},22:{NULL:NULL},22:{2:"unique"}}
    EXPECT_EQ(2, col->get(5).get_map().size());
    EXPECT_EQ("{11:{2:NULL,1:NULL},22:{2:'unique'}}", col->debug_item(5));

    // write
    io::FormattedOutputStreamString buff;
    ASSERT_TRUE(conv->write_string(&buff, *col, 0, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 1, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 2, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 3, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 4, Converter::Options()).ok());
    ASSERT_TRUE(conv->write_string(&buff, *col, 5, Converter::Options()).ok());
    ASSERT_TRUE(buff.finalize().ok());
    ASSERT_EQ(
            "{}{1:{}}{1:{1:\"abc\",2:null},2:{3:\"\"}}{null:{null:null}}{null:{null:\"NULL\"},-20308764:{33:\"\"}}{11:{"
            "2:null,1:null},22:{2:\"unique\"}}",
            buff.as_string());
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_text_map) {
    // map<string,string> stored in Hive LazySimpleSerDe text format:
    // entries separated by \002 (^B), key/value by \003 (^C), no enclosing braces.
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 6000;
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 6000;

    auto options = Converter::Options();
    options.array_format_type = ArrayFormatType::kHive;
    options.array_hive_collection_delimiter = '\002';
    options.array_hive_mapkey_delimiter = '\003';
    options.array_hive_nested_level = 1;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    // empty map
    EXPECT_TRUE(conv->read_string(col.get(), "", options));
    // single entry
    EXPECT_TRUE(conv->read_string(col.get(), std::string("k1\003v1", 6), options));
    // two entries
    EXPECT_TRUE(conv->read_string(col.get(), std::string("k1\003v1\002k2\003v2", 12), options));

    EXPECT_EQ(3, col->size());
    // {}
    EXPECT_EQ(0, col->get(0).get_map().size());
    EXPECT_EQ("{}", col->debug_item(0));
    // {'k1':'v1'}
    EXPECT_EQ(1, col->get(1).get_map().size());
    EXPECT_EQ("{'k1':'v1'}", col->debug_item(1));
    // {'k1':'v1','k2':'v2'}
    EXPECT_EQ(2, col->get(2).get_map().size());
    EXPECT_EQ("{'k1':'v1','k2':'v2'}", col->debug_item(2));
}

// NOLINTNEXTLINE
TEST(MapConverterTest, test_read_hive_text_map_with_special_chars_in_value) {
    TypeDescriptor t(TYPE_MAP);
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 65533;
    t.children.emplace_back(TYPE_VARCHAR);
    t.children.back().len = 65533;

    auto options = Converter::Options();
    options.array_format_type = ArrayFormatType::kHive;
    options.array_hive_collection_delimiter = '\002';
    options.array_hive_mapkey_delimiter = '\003';
    options.array_hive_nested_level = 1;

    auto conv = csv::get_converter(t, false);
    auto col = ColumnHelper::create_column(t, false);

    // Values containing braces/quotes/colons should not break Hive map parsing.
    std::string map_with_braces = std::string("pltvFactor\0031.0\002ext_filed\003{a=1,b={c:d}}\002flag\003\"quoted\"");
    EXPECT_TRUE(conv->read_string(col.get(), map_with_braces, options));

    // Value containing extra mapkey delimiter bytes should stay in value (Hive behavior).
    std::string map_with_extra_kv = std::string("k1\003v1\003extra\002k2\003v2");
    EXPECT_TRUE(conv->read_string(col.get(), map_with_extra_kv, options));

    EXPECT_EQ(2, col->size());
    EXPECT_EQ(3, col->get(0).get_map().size());
    EXPECT_EQ(2, col->get(1).get_map().size());
    // Old parser treated braces/quotes as nesting and failed; Hive parser accepts them.
    EXPECT_EQ("{'pltvFactor':'1.0','ext_filed':'{a=1,b={c:d}}','flag':'\"quoted\"'}", col->debug_item(0));
}

} // namespace starrocks::csv