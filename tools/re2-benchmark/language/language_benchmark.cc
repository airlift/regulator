// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "language_harness.h"

#include <re2/re2.h>

#include <optional>

namespace {
// Where the search after `match` starts: its end, or one UTF-8 scalar later after an empty
// match, matching the public Slice iterator. Past the input end when nothing remains.
size_t next_search(absl::string_view input, absl::string_view match) {
    size_t next = static_cast<size_t>(match.data() - input.data()) + match.size();
    if (!match.empty()) return next;
    if (next == input.size()) return next + 1;
    unsigned char c = input[next];
    return next + (c < 0x80 ? 1 : c < 0xe0 ? 2 : c < 0xf0 ? 3 : 4);
}

struct Re2Engine {
    static RE2 compile(const std::string& pattern) { return RE2(pattern); }

    static void check(const Inputs&) {}

    static bool contains(const RE2& pattern, absl::string_view input) {
        return pattern.Match(input, 0, input.size(), RE2::UNANCHORED, nullptr, 0);
    }

    static int64_t count(const RE2& pattern, absl::string_view input, std::vector<std::string>* matches = nullptr) {
        absl::string_view match;
        size_t next = 0;
        int64_t count = 0;
        while (next <= input.size() && pattern.Match(input, next, input.size(), RE2::UNANCHORED, &match, 1)) {
            ++count;
            if (matches != nullptr) matches->push_back(encode_hex(match));
            next = next_search(input, match);
        }
        return count;
    }

    // The rewrite `\N` of the operation's group, and room for the groups up to it.
    struct Scratch {
        std::string rewrite;
        std::vector<absl::string_view> groups;
    };

    static constexpr bool replaces = true;

    // A rewrite takes one digit after the backslash, so `\10` would name group 1 and a literal 0.
    static void check_specialized(Specialized operation, int group) {
        if (operation != Specialized::ExtractAll && group > 9) {
            throw std::runtime_error("RE2 rewrites cannot name capture group " + std::to_string(group));
        }
    }

    static Scratch scratch(const RE2&, int group) {
        return {"\\" + std::to_string(group), std::vector<absl::string_view>(group + 1)};
    }

    // GlobalReplace rewrites a string in place, so each call copies the input first.
    static std::string replace(const RE2& pattern, Scratch& scratch, absl::string_view input,
            std::vector<std::string>* trace) {
        std::string text(input);
        RE2::GlobalReplace(&text, pattern, scratch.rewrite);
        if (trace != nullptr) trace->push_back(encode_hex(text));
        return text;
    }

    static std::optional<std::string> extract(const RE2& pattern, Scratch& scratch, absl::string_view input, int,
            std::vector<std::string>* trace) {
        std::string value;
        if (!RE2::Extract(input, pattern, scratch.rewrite, &value)) return std::nullopt;
        if (trace != nullptr) trace->push_back(encode_hex(value));
        return value;
    }

    static std::vector<absl::string_view> extract_all(const RE2& pattern, Scratch& scratch, absl::string_view input,
            int group, std::vector<std::string>* trace) {
        std::vector<absl::string_view> values;
        size_t next = 0;
        while (next <= input.size() &&
                pattern.Match(input, next, input.size(), RE2::UNANCHORED, scratch.groups.data(), group + 1)) {
            values.push_back(scratch.groups[group]);
            next = next_search(input, scratch.groups[0]);
        }
        if (trace != nullptr) {
            for (absl::string_view value : values) trace->push_back(value.data() == nullptr ? "-" : encode_hex(value));
        }
        return values;
    }
};
} // namespace

int main(int argc, char** argv) {
    return run<Re2Engine>(argc, argv, "language_benchmark");
}
