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

namespace {
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
            next = static_cast<size_t>(match.data() - input.data()) + match.size();
            // Advance by one UTF-8 scalar after an empty match, matching the public Slice iterator.
            if (match.empty()) {
                if (next == input.size()) break;
                unsigned char c = input[next];
                next += c < 0x80 ? 1 : c < 0xe0 ? 2 : c < 0xf0 ? 3 : 4;
            }
        }
        return count;
    }
};
} // namespace

int main(int argc, char** argv) {
    return run<Re2Engine>(argc, argv, "language_benchmark");
}
