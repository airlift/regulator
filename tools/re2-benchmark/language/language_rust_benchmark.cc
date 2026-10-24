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
#include "rust-regex/language_rust_regex.h"

namespace {
struct RustEngine {
    static RustRegex compile(const std::string& pattern) { return RustRegex(pattern, true, false); }

    // The crate matches only text, so check every input before any timing.
    static void check(const Inputs& inputs) {
        if (!valid_utf8(inputs.pattern)) throw std::runtime_error("pattern is not UTF-8");
        for (const Source& source : inputs.sources) {
            if (!valid_utf8(source.bytes())) throw std::runtime_error("input is not UTF-8");
        }
    }

    static bool contains(const RustRegex& pattern, std::string_view input) {
        return language_rust_regex_is_match(pattern.get(), input.data(), input.size());
    }

    static int64_t count(const RustRegex& pattern, std::string_view input, std::vector<std::string>* matches = nullptr) {
        if (matches == nullptr) return language_rust_regex_count(pattern.get(), input.data(), input.size());
        struct Trace {
            std::string_view input;
            std::vector<std::string>* matches;
        } trace{input, matches};
        auto record = [](void* context, const size_t* spans, size_t) {
            auto* trace = static_cast<Trace*>(context);
            trace->matches->push_back(encode_hex(trace->input.substr(spans[0], spans[1] - spans[0])));
        };
        if (!language_rust_regex_trace(pattern.get(), input.data(), input.size(), false, record, &trace)) {
            throw std::runtime_error("timed iteration differs from the crate's iteration");
        }
        return matches->size();
    }
};
} // namespace

int main(int argc, char** argv) {
    return run<RustEngine>(argc, argv, "language_rust_benchmark");
}
