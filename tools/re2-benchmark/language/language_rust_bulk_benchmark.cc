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
#include "rust-regex/language_rust_regex.h"

#include <memory>

namespace {
struct FreeCaptureLocations {
    void operator()(LanguageRustCaptureLocations* locations) const { language_rust_regex_free_capture_locations(locations); }
};

using CaptureLocations = std::unique_ptr<LanguageRustCaptureLocations, FreeCaptureLocations>;

struct RustEngine {
    static RustRegex compile(const Inputs& inputs) { return RustRegex(inputs.pattern, inputs.unicode, inputs.insensitive); }

    // The crate matches only text, so check every input before any timing.
    static void check(const Inputs& inputs) {
        if (!valid_utf8(inputs.pattern)) throw std::runtime_error("pattern is not UTF-8");
        if (inputs.model != "compile" && !valid_utf8(inputs.haystack)) throw std::runtime_error("haystack is not UTF-8");
    }

    static CaptureLocations groups(const Inputs& inputs, const RustRegex& pattern) {
        return CaptureLocations(inputs.captures() ? language_rust_regex_capture_locations(pattern.get()) : nullptr);
    }

    static int64_t execute(const Inputs& inputs, const RustRegex& pattern, const std::vector<std::string_view>& sources,
                           CaptureLocations& groups, bool trace) {
        bool captures = inputs.captures();
        int64_t result = 0;
        for (std::string_view source : sources) {
            if (trace) std::cout << "source\n";
            if (inputs.model == "grep") {
                bool found = language_rust_regex_is_match(pattern.get(), source.data(), source.size());
                result += found;
                if (trace) std::cout << (found ? "1\n" : "0\n");
                continue;
            }
            if (trace) {
                write_trace(pattern, source, captures);
                continue;
            }
            if (captures) result += language_rust_regex_count_captures(pattern.get(), groups.get(), source.data(), source.size());
            else if (inputs.model == "count-spans") result += language_rust_regex_count_spans(pattern.get(), source.data(), source.size());
            else result += language_rust_regex_count(pattern.get(), source.data(), source.size());
        }
        return result;
    }

    static void write_trace(const RustRegex& pattern, std::string_view source, bool captures) {
        auto write = [](void* context, const size_t* spans, size_t groups) {
            std::string_view source = *static_cast<std::string_view*>(context);
            for (size_t group = 0; group < groups; ++group) {
                size_t start = spans[2 * group];
                write_group(source, start == SIZE_MAX ? std::string_view() : source.substr(start, spans[2 * group + 1] - start));
            }
            std::cout.put('\n');
        };
        if (!language_rust_regex_trace(pattern.get(), source.data(), source.size(), captures, write, &source)) {
            throw std::runtime_error("timed iteration differs from the crate's iteration");
        }
    }
};
} // namespace

int main(int argc, char** argv) {
    return run<RustEngine>(argc, argv);
}
