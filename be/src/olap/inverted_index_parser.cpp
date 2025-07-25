// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "olap/inverted_index_parser.h"

#include "olap/rowset/segment_v2/inverted_index/char_filter/char_filter_factory.h"
#include "util/string_util.h"

namespace doris {

std::string inverted_index_parser_type_to_string(InvertedIndexParserType parser_type) {
    switch (parser_type) {
    case InvertedIndexParserType::PARSER_NONE:
        return INVERTED_INDEX_PARSER_NONE;
    case InvertedIndexParserType::PARSER_STANDARD:
        return INVERTED_INDEX_PARSER_STANDARD;
    case InvertedIndexParserType::PARSER_UNICODE:
        return INVERTED_INDEX_PARSER_UNICODE;
    case InvertedIndexParserType::PARSER_ENGLISH:
        return INVERTED_INDEX_PARSER_ENGLISH;
    case InvertedIndexParserType::PARSER_CHINESE:
        return INVERTED_INDEX_PARSER_CHINESE;
    case InvertedIndexParserType::PARSER_ICU:
        return INVERTED_INDEX_PARSER_ICU;
    case InvertedIndexParserType::PARSER_BASIC:
        return INVERTED_INDEX_PARSER_BASIC;
    case InvertedIndexParserType::PARSER_IK:
        return INVERTED_INDEX_PARSER_IK;
    default:
        return INVERTED_INDEX_PARSER_UNKNOWN;
    }
}

InvertedIndexParserType get_inverted_index_parser_type_from_string(const std::string& parser_str) {
    auto parser_str_lower = to_lower(parser_str);
    if (parser_str_lower == INVERTED_INDEX_PARSER_NONE) {
        return InvertedIndexParserType::PARSER_NONE;
    } else if (parser_str_lower == INVERTED_INDEX_PARSER_STANDARD) {
        return InvertedIndexParserType::PARSER_STANDARD;
    } else if (parser_str_lower == INVERTED_INDEX_PARSER_UNICODE) {
        return InvertedIndexParserType::PARSER_UNICODE;
    } else if (parser_str_lower == INVERTED_INDEX_PARSER_ENGLISH) {
        return InvertedIndexParserType::PARSER_ENGLISH;
    } else if (parser_str_lower == INVERTED_INDEX_PARSER_CHINESE) {
        return InvertedIndexParserType::PARSER_CHINESE;
    } else if (parser_str_lower == INVERTED_INDEX_PARSER_ICU) {
        return InvertedIndexParserType::PARSER_ICU;
    } else if (parser_str_lower == INVERTED_INDEX_PARSER_BASIC) {
        return InvertedIndexParserType::PARSER_BASIC;
    } else if (parser_str_lower == INVERTED_INDEX_PARSER_IK) {
        return InvertedIndexParserType::PARSER_IK;
    }

    return InvertedIndexParserType::PARSER_UNKNOWN;
}

std::string get_parser_string_from_properties(
        const std::map<std::string, std::string>& properties) {
    if (properties.find(INVERTED_INDEX_PARSER_KEY) != properties.end()) {
        return properties.at(INVERTED_INDEX_PARSER_KEY);
    } else {
        return INVERTED_INDEX_PARSER_NONE;
    }
}

std::string get_parser_mode_string_from_properties(
        const std::map<std::string, std::string>& properties) {
    if (properties.find(INVERTED_INDEX_PARSER_MODE_KEY) != properties.end()) {
        return properties.at(INVERTED_INDEX_PARSER_MODE_KEY);
    } else {
        auto parser_it = properties.find(INVERTED_INDEX_PARSER_KEY);
        if (parser_it != properties.end() && parser_it->second == INVERTED_INDEX_PARSER_IK) {
            return INVERTED_INDEX_PARSER_SMART;
        }
        return INVERTED_INDEX_PARSER_COARSE_GRANULARITY;
    }
}

std::string get_parser_phrase_support_string_from_properties(
        const std::map<std::string, std::string>& properties) {
    if (properties.find(INVERTED_INDEX_PARSER_PHRASE_SUPPORT_KEY) != properties.end()) {
        return properties.at(INVERTED_INDEX_PARSER_PHRASE_SUPPORT_KEY);
    } else {
        return INVERTED_INDEX_PARSER_PHRASE_SUPPORT_NO;
    }
}

CharFilterMap get_parser_char_filter_map_from_properties(
        const std::map<std::string, std::string>& properties) {
    CharFilterMap char_filter_map;

    if (properties.find(INVERTED_INDEX_PARSER_CHAR_FILTER_TYPE) == properties.end()) {
        return CharFilterMap();
    }

    std::string type = properties.at(INVERTED_INDEX_PARSER_CHAR_FILTER_TYPE);
    if (type == INVERTED_INDEX_CHAR_FILTER_CHAR_REPLACE) {
        // type
        char_filter_map[INVERTED_INDEX_PARSER_CHAR_FILTER_TYPE] =
                INVERTED_INDEX_CHAR_FILTER_CHAR_REPLACE;

        // pattern
        if (properties.find(INVERTED_INDEX_PARSER_CHAR_FILTER_PATTERN) == properties.end()) {
            return CharFilterMap();
        }
        std::string pattern = properties.at(INVERTED_INDEX_PARSER_CHAR_FILTER_PATTERN);
        char_filter_map[INVERTED_INDEX_PARSER_CHAR_FILTER_PATTERN] = pattern;

        // placement
        std::string replacement = " ";
        if (properties.find(INVERTED_INDEX_PARSER_CHAR_FILTER_REPLACEMENT) != properties.end()) {
            replacement = properties.at(INVERTED_INDEX_PARSER_CHAR_FILTER_REPLACEMENT);
        }
        char_filter_map[INVERTED_INDEX_PARSER_CHAR_FILTER_REPLACEMENT] = replacement;
    } else {
        return CharFilterMap();
    }

    return char_filter_map;
}

std::string get_parser_ignore_above_value_from_properties(
        const std::map<std::string, std::string>& properties) {
    if (properties.find(INVERTED_INDEX_PARSER_IGNORE_ABOVE_KEY) != properties.end()) {
        return properties.at(INVERTED_INDEX_PARSER_IGNORE_ABOVE_KEY);
    } else {
        return INVERTED_INDEX_PARSER_IGNORE_ABOVE_VALUE;
    }
}

std::string get_parser_stopwords_from_properties(
        const std::map<std::string, std::string>& properties) {
    DBUG_EXECUTE_IF("inverted_index_parser.get_parser_stopwords_from_properties", { return ""; })
    if (properties.find(INVERTED_INDEX_PARSER_STOPWORDS_KEY) != properties.end()) {
        return properties.at(INVERTED_INDEX_PARSER_STOPWORDS_KEY);
    } else {
        return "";
    }
}

std::string get_parser_dict_compression_from_properties(
        const std::map<std::string, std::string>& properties) {
    if (properties.find(INVERTED_INDEX_PARSER_DICT_COMPRESSION_KEY) != properties.end()) {
        return properties.at(INVERTED_INDEX_PARSER_DICT_COMPRESSION_KEY);
    } else {
        return "";
    }
}

std::string get_custom_analyzer_string_from_properties(
        const std::map<std::string, std::string>& properties) {
    if (properties.find(INVERTED_INDEX_CUSTOM_ANALYZER_KEY) != properties.end()) {
        return properties.at(INVERTED_INDEX_CUSTOM_ANALYZER_KEY);
    } else {
        return "";
    }
}

} // namespace doris
