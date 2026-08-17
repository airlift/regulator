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

#include <benchmark/benchmark.h>
#include <re2/re2.h>

#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Inputs {
    std::string pattern;
    std::string haystack;
    std::string model;
    bool unicode;
    bool insensitive;

    RE2::Options options() const {
        RE2::Options options;
        options.set_encoding(unicode ? RE2::Options::EncodingUTF8 : RE2::Options::EncodingLatin1);
        options.set_case_sensitive(!insensitive);
        return options;
    }

    std::vector<absl::string_view> sources() const {
        if (model != "grep" && model != "grep-captures") return {haystack};
        std::vector<absl::string_view> lines;
        size_t start = 0;
        while (start < haystack.size()) {
            size_t end = haystack.find('\n', start);
            if (end == std::string::npos) end = haystack.size();
            size_t length = end - start;
            if (length && haystack[end - 1] == '\r') --length;
            lines.emplace_back(haystack.data() + start, length);
            start = end + 1;
        }
        return lines;
    }
};

Inputs read_inputs(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("cannot open workload");
    std::string payload((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    std::map<std::string, std::string> fields;
    size_t position = 0;
    while (position < payload.size()) {
        size_t first = payload.find(':', position);
        size_t second = payload.find(':', first + 1);
        if (first == std::string::npos || second == std::string::npos) throw std::runtime_error("invalid KLV field");
        std::string key = payload.substr(position, first - position);
        std::string length_text = payload.substr(first + 1, second - first - 1);
        if (length_text.empty() || length_text.find_first_not_of("0123456789") != std::string::npos) {
            throw std::runtime_error("invalid KLV length");
        }
        size_t length = std::stoull(length_text);
        if (length >= payload.size() - second || second + length + 1 >= payload.size() ||
                payload[second + length + 1] != '\n') throw std::runtime_error("truncated KLV value");
        if (!fields.emplace(key, payload.substr(second + 1, length)).second) throw std::runtime_error("duplicate KLV field");
        position = second + length + 2;
    }
    for (const char* flag : {"unicode", "case-insensitive"}) {
        if (fields.at(flag) != "true" && fields.at(flag) != "false") throw std::runtime_error("invalid KLV flag");
    }
    std::string model = fields.at("model");
    if (model != "compile" && model != "count" && model != "count-spans" && model != "count-captures" &&
            model != "grep" && model != "grep-captures") throw std::runtime_error("unknown model");
    return {fields.at("pattern"), fields.at("haystack"), model,
            fields.at("unicode") == "true", fields.at("case-insensitive") == "true"};
}

void write_group(absl::string_view source, absl::string_view value) {
    if (value.data() == nullptr) { std::cout << "-;"; return; }
    size_t start = value.data() - source.data();
    std::cout << start << ':' << start + value.size() << ':';
    const char* hex = "0123456789abcdef";
    for (unsigned char byte : value) {
        std::cout.put(hex[byte >> 4]);
        std::cout.put(hex[byte & 15]);
    }
    std::cout.put(';');
}

int64_t execute(const Inputs& inputs, const RE2& pattern, const std::vector<absl::string_view>& sources,
                std::vector<absl::string_view>& groups, bool trace) {
    bool captures = inputs.model == "count-captures" || inputs.model == "grep-captures";
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
} // namespace

int main(int argc, char** argv) {
    try {
        if (argc < 3) throw std::runtime_error("usage: --trace|--execute <workload> or <workload> <compile|execute> [benchmark flags]");
        bool verify = std::string(argv[1]) == "--trace" || std::string(argv[1]) == "--execute";
        Inputs inputs = read_inputs(argv[verify ? 2 : 1]);
        std::cerr << "phase=compile\n";
        RE2 pattern(inputs.pattern, inputs.options());
        if (!pattern.ok()) throw std::runtime_error(pattern.error());
        auto sources = inputs.sources();
        bool captures = inputs.model == "count-captures" || inputs.model == "grep-captures";
        std::vector<absl::string_view> groups(captures ? pattern.NumberOfCapturingGroups() + 1 : 1);
        if (verify) {
            if (inputs.model == "compile") std::cout << "compiled\n";
            else {
                std::cerr << "phase=execution\n";
                bool trace = std::string(argv[1]) == "--trace";
                int64_t result = execute(inputs, pattern, sources, groups, trace);
                if (!trace) std::cout << result << '\n';
            }
            return 0;
        }
        std::string operation = argv[2];
        if (operation != (inputs.model == "compile" ? "compile" : "execute")) throw std::runtime_error("operation/model mismatch");
        benchmark::RegisterBenchmark(operation.c_str(), [&](benchmark::State& state) {
            if (operation == "compile") {
                for (auto _ : state) {
                    RE2 compiled(inputs.pattern, inputs.options());
                    benchmark::DoNotOptimize(compiled);
                }
            }
            else {
                for (auto _ : state) benchmark::DoNotOptimize(execute(inputs, pattern, sources, groups, false));
            }
        });
        for (int i = 3; i < argc; ++i) argv[i - 2] = argv[i];
        argc -= 2;
        benchmark::Initialize(&argc, argv);
        if (benchmark::ReportUnrecognizedArguments(argc, argv)) return 1;
        benchmark::RunSpecifiedBenchmarks();
        benchmark::Shutdown();
        return 0;
    }
    catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
