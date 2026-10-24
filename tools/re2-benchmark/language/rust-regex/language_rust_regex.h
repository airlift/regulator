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

#pragma once

// The C interface of src/lib.rs and an owner for its compiled regex.

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

extern "C" {
struct LanguageRustRegex;
struct LanguageRustCaptureLocations;
typedef void (*LanguageRustMatchCallback)(void* context, const size_t* spans, size_t groups);

bool language_rust_regex_valid_utf8(const char* data, size_t length);
LanguageRustRegex* language_rust_regex_compile(const char* pattern, size_t length, bool unicode, bool case_insensitive,
                                               char** error);
void language_rust_regex_free(LanguageRustRegex* regex);
void language_rust_regex_free_error(char* error);
LanguageRustCaptureLocations* language_rust_regex_capture_locations(const LanguageRustRegex* regex);
void language_rust_regex_free_capture_locations(LanguageRustCaptureLocations* locations);
bool language_rust_regex_is_match(const LanguageRustRegex* regex, const char* data, size_t length);
uint64_t language_rust_regex_count(const LanguageRustRegex* regex, const char* data, size_t length);
uint64_t language_rust_regex_count_spans(const LanguageRustRegex* regex, const char* data, size_t length);
uint64_t language_rust_regex_count_captures(const LanguageRustRegex* regex, LanguageRustCaptureLocations* locations,
                                            const char* data, size_t length);
bool language_rust_regex_trace(const LanguageRustRegex* regex, const char* data, size_t length, bool captures,
                               LanguageRustMatchCallback callback, void* context);
}

namespace {
class RustRegex {
public:
    RustRegex(std::string_view pattern, bool unicode, bool case_insensitive)
            : regex_(language_rust_regex_compile(pattern.data(), pattern.size(), unicode, case_insensitive, &error_)) {}
    ~RustRegex() {
        if (regex_ != nullptr) language_rust_regex_free(regex_);
        if (error_ != nullptr) language_rust_regex_free_error(error_);
    }
    RustRegex(const RustRegex&) = delete;
    RustRegex& operator=(const RustRegex&) = delete;

    bool ok() const { return regex_ != nullptr; }
    std::string error() const { return error_; }
    const LanguageRustRegex* get() const { return regex_; }

private:
    // Declared before regex_, whose initializer writes it when compiling fails.
    char* error_ = nullptr;
    LanguageRustRegex* regex_;
};

bool valid_utf8(std::string_view text) { return language_rust_regex_valid_utf8(text.data(), text.size()); }
} // namespace
