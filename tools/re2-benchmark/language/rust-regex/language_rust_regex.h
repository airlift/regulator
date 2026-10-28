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

// The C interface of src/lib.rs and an owner for its compiled regex. The str and
// bytes APIs each have their own entry points and opaque compiled type.

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
typedef void (*LanguageRustValueCallback)(void* context, const char* data, size_t length);
bool language_rust_regex_extract(const LanguageRustRegex* regex, LanguageRustCaptureLocations* locations,
                                 const char* data, size_t length, size_t group, const char** value,
                                 size_t* value_length);
uint64_t language_rust_regex_extract_all(const LanguageRustRegex* regex, LanguageRustCaptureLocations* locations,
                                         const char* data, size_t length, size_t group,
                                         LanguageRustValueCallback callback, void* context);

struct LanguageRustBytesRegex;
struct LanguageRustBytesCaptureLocations;

LanguageRustBytesRegex* language_rust_bytes_regex_compile(const char* pattern, size_t length, bool unicode,
                                                          bool case_insensitive, char** error);
void language_rust_bytes_regex_free(LanguageRustBytesRegex* regex);
LanguageRustBytesCaptureLocations* language_rust_bytes_regex_capture_locations(const LanguageRustBytesRegex* regex);
void language_rust_bytes_regex_free_capture_locations(LanguageRustBytesCaptureLocations* locations);
bool language_rust_bytes_regex_is_match(const LanguageRustBytesRegex* regex, const char* data, size_t length);
uint64_t language_rust_bytes_regex_count(const LanguageRustBytesRegex* regex, const char* data, size_t length);
uint64_t language_rust_bytes_regex_count_spans(const LanguageRustBytesRegex* regex, const char* data, size_t length);
uint64_t language_rust_bytes_regex_count_captures(const LanguageRustBytesRegex* regex,
                                                  LanguageRustBytesCaptureLocations* locations, const char* data,
                                                  size_t length);
bool language_rust_bytes_regex_trace(const LanguageRustBytesRegex* regex, const char* data, size_t length,
                                     bool captures, LanguageRustMatchCallback callback, void* context);
}

namespace {
template <typename Handle, Handle* (*Compile)(const char*, size_t, bool, bool, char**), void (*Free)(Handle*)>
class BasicRustRegex {
public:
    BasicRustRegex(std::string_view pattern, bool unicode, bool case_insensitive)
            : regex_(Compile(pattern.data(), pattern.size(), unicode, case_insensitive, &error_)) {}
    ~BasicRustRegex() {
        if (regex_ != nullptr) Free(regex_);
        if (error_ != nullptr) language_rust_regex_free_error(error_);
    }
    BasicRustRegex(const BasicRustRegex&) = delete;
    BasicRustRegex& operator=(const BasicRustRegex&) = delete;

    bool ok() const { return regex_ != nullptr; }
    std::string error() const { return error_; }
    const Handle* get() const { return regex_; }

private:
    // Declared before regex_, whose initializer writes it when compiling fails.
    char* error_ = nullptr;
    Handle* regex_;
};

using RustRegex = BasicRustRegex<LanguageRustRegex, language_rust_regex_compile, language_rust_regex_free>;
using RustBytesRegex =
        BasicRustRegex<LanguageRustBytesRegex, language_rust_bytes_regex_compile, language_rust_bytes_regex_free>;

bool valid_utf8(std::string_view text) { return language_rust_regex_valid_utf8(text.data(), text.size()); }
} // namespace
