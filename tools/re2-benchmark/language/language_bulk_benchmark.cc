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

#include "language_bulk_harness.h"

#include <re2/re2.h>

namespace {
struct Re2Engine {
    static RE2::Options options(const Inputs& inputs) {
        RE2::Options options;
        options.set_encoding(inputs.unicode ? RE2::Options::EncodingUTF8 : RE2::Options::EncodingLatin1);
        options.set_case_sensitive(!inputs.insensitive);
        return options;
    }

    static RE2 compile(const Inputs& inputs) { return RE2(inputs.pattern, options(inputs)); }

    static void check(const Inputs&) {}

    static std::vector<absl::string_view> groups(const Inputs& inputs, const RE2& pattern) {
        return std::vector<absl::string_view>(inputs.captures() ? pattern.NumberOfCapturingGroups() + 1 : 1);
    }

    static int64_t execute(const Inputs& inputs, const RE2& pattern, const std::vector<absl::string_view>& sources,
                           std::vector<absl::string_view>& groups, bool trace) {
        bool captures = inputs.captures();
        int64_t result = 0;
        for (absl::string_view source : sources) {
            if (trace) std::cout << "source\n";
            if (inputs.model == "grep") {
                bool found = pattern.Match(source, 0, source.size(), RE2::UNANCHORED, nullptr, 0);
                result += found;
                if (trace) std::cout << (found ? "1\n" : "0\n");
                continue;
            }
            size_t next = 0;
            while (next <= source.size() && pattern.Match(source, next, source.size(), RE2::UNANCHORED, groups.data(), groups.size())) {
                if (captures) {
                    for (absl::string_view group : groups) result += group.data() != nullptr;
                }
                else if (inputs.model == "count-spans") result += groups[0].size();
                else ++result;
                if (trace) {
                    for (absl::string_view group : groups) write_group(source, group);
                    std::cout.put('\n');
                }
                next = groups[0].data() - source.data() + groups[0].size();
                if (groups[0].empty()) {
                    if (next == source.size()) break;
                    unsigned char byte = source[next];
                    next += !inputs.unicode || byte < 0x80 ? 1 : byte < 0xe0 ? 2 : byte < 0xf0 ? 3 : 4;
                }
            }
        }
        return result;
    }
};
} // namespace

int main(int argc, char** argv) {
    return run<Re2Engine>(argc, argv);
}
