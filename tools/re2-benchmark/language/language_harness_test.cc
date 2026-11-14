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

// A runner over a stand-in engine for testing the harness's own checks, never for timing. The engine
// matches its pattern as a literal, and its find moves the end of a match by one once an input
// comes round again, so only the second pass through the rotation sees wrong bounds, with the same
// match or no match.

#include "language_harness.h"

#include <set>

namespace {
struct Literal {
    std::string text;

    bool ok() const { return true; }
    std::string error() const { return ""; }
};

struct DriftingFindEngine {
    static Literal compile(const std::string& pattern) { return {pattern}; }

    static void check(const Inputs& inputs) {
        if (inputs.pattern.empty()) throw std::runtime_error("the stand-in engine needs a non-empty literal");
    }

    static bool contains(const Literal& pattern, std::string_view input) {
        return input.find(pattern.text) != std::string_view::npos;
    }

    static int64_t find(const Literal& pattern, std::string_view input) {
        static std::set<const char*> seen;
        size_t start = input.find(pattern.text);
        if (start == std::string_view::npos) return kNoMatch;
        size_t drift = seen.insert(input.data()).second ? 0 : 1;
        return match_bounds(start, start + pattern.text.size() + drift);
    }

    static int64_t count(const Literal& pattern, std::string_view input, std::vector<std::string>* matches = nullptr) {
        int64_t count = 0;
        for (size_t next = input.find(pattern.text); next != std::string_view::npos;
                next = input.find(pattern.text, next + pattern.text.size())) {
            ++count;
            if (matches != nullptr) matches->push_back(encode_hex(pattern.text));
        }
        return count;
    }
};
} // namespace

int main(int argc, char** argv) {
    try {
        if (argc != 4 || std::string(argv[1]) != "--verify-operation") {
            throw std::runtime_error("usage: language_harness_test --verify-operation <workload> <operation>");
        }
        Inputs inputs = read_inputs(argv[2]);
        DriftingFindEngine::check(inputs);
        verify_operation<DriftingFindEngine>(inputs, argv[3]);
        return 0;
    }
    catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
