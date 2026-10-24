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
// The entry points of the crate's two APIs. The runner picks one before any timing,
// so every timed call goes straight to the crate's public work for that API.
struct StrApi {
    using Regex = RustRegex;
    using Locations = LanguageRustCaptureLocations;
    static constexpr auto capture_locations = language_rust_regex_capture_locations;
    static constexpr auto free_capture_locations = language_rust_regex_free_capture_locations;
    static constexpr auto is_match = language_rust_regex_is_match;
    static constexpr auto count = language_rust_regex_count;
    static constexpr auto count_spans = language_rust_regex_count_spans;
    static constexpr auto count_captures = language_rust_regex_count_captures;
    static constexpr auto trace = language_rust_regex_trace;
};

struct BytesApi {
    using Regex = RustBytesRegex;
    using Locations = LanguageRustBytesCaptureLocations;
    static constexpr auto capture_locations = language_rust_bytes_regex_capture_locations;
    static constexpr auto free_capture_locations = language_rust_bytes_regex_free_capture_locations;
    static constexpr auto is_match = language_rust_bytes_regex_is_match;
    static constexpr auto count = language_rust_bytes_regex_count;
    static constexpr auto count_spans = language_rust_bytes_regex_count_spans;
    static constexpr auto count_captures = language_rust_bytes_regex_count_captures;
    static constexpr auto trace = language_rust_bytes_regex_trace;
};

template <typename Api>
struct FreeCaptureLocations {
    void operator()(typename Api::Locations* locations) const { Api::free_capture_locations(locations); }
};

template <typename Api>
using CaptureLocations = std::unique_ptr<typename Api::Locations, FreeCaptureLocations<Api>>;

template <typename Api>
struct RustEngine {
    using Regex = typename Api::Regex;

    static Regex compile(const Inputs& inputs) { return Regex(inputs.pattern, inputs.unicode, inputs.insensitive); }

    // Both APIs parse the pattern as text, so check it before any timing.
    static void check(const Inputs& inputs) {
        if (!valid_utf8(inputs.pattern)) throw std::runtime_error("pattern is not UTF-8");
    }

    static CaptureLocations<Api> groups(const Inputs& inputs, const Regex& pattern) {
        return CaptureLocations<Api>(inputs.captures() ? Api::capture_locations(pattern.get()) : nullptr);
    }

    static int64_t execute(const Inputs& inputs, const Regex& pattern, const std::vector<std::string_view>& sources,
                           CaptureLocations<Api>& groups, bool trace) {
        bool captures = inputs.captures();
        int64_t result = 0;
        for (std::string_view source : sources) {
            if (trace) std::cout << "source\n";
            if (inputs.model == "grep") {
                bool found = Api::is_match(pattern.get(), source.data(), source.size());
                result += found;
                if (trace) std::cout << (found ? "1\n" : "0\n");
                continue;
            }
            if (trace) {
                write_trace(pattern, source, captures);
                continue;
            }
            if (captures) result += Api::count_captures(pattern.get(), groups.get(), source.data(), source.size());
            else if (inputs.model == "count-spans") result += Api::count_spans(pattern.get(), source.data(), source.size());
            else result += Api::count(pattern.get(), source.data(), source.size());
        }
        return result;
    }

    static void write_trace(const Regex& pattern, std::string_view source, bool captures) {
        auto write = [](void* context, const size_t* spans, size_t groups) {
            std::string_view source = *static_cast<std::string_view*>(context);
            for (size_t group = 0; group < groups; ++group) {
                size_t start = spans[2 * group];
                write_group(source, start == SIZE_MAX ? std::string_view() : source.substr(start, spans[2 * group + 1] - start));
            }
            std::cout.put('\n');
        };
        if (!Api::trace(pattern.get(), source.data(), source.size(), captures, write, &source)) {
            throw std::runtime_error("timed iteration differs from the crate's iteration");
        }
    }
};

// As RE2's runner switches to Latin-1 when the source turns Unicode off, Rust uses the
// bytes API then, and for a haystack that is not UTF-8. This choice is what keeps such a
// haystack away from the str API, which borrows it as text without checking.
bool bytes_api(const Inputs& inputs) { return !inputs.unicode || !valid_utf8(inputs.haystack); }
} // namespace

int main(int argc, char** argv) {
    return run_selected(argc, argv, [&](bool verify, const Inputs& inputs) {
        return bytes_api(inputs) ? run_engine<RustEngine<BytesApi>>(argc, argv, verify, inputs)
                                 : run_engine<RustEngine<StrApi>>(argc, argv, verify, inputs);
    });
}
