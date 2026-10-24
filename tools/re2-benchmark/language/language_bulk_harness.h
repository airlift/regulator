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

// Bulk harness shared by the native comparators. An engine adapter supplies
// compile (returning a pattern with ok() and error()), check, which rejects inputs
// the engine cannot accept, groups, which allocates per-search state outside
// timing, and execute, which runs one Rebar model and optionally writes its trace.

#include <benchmark/benchmark.h>

#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {
struct Inputs {
    std::string pattern;
    std::string haystack;
    std::string model;
    bool unicode;
    bool insensitive;

    bool captures() const { return model == "count-captures" || model == "grep-captures"; }

    std::vector<std::string_view> sources() const {
        if (model != "grep" && model != "grep-captures") return {haystack};
        std::vector<std::string_view> lines;
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

void write_group(std::string_view source, std::string_view value) {
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

template <typename Engine>
int run(int argc, char** argv) {
    try {
        if (argc < 3) throw std::runtime_error("usage: --trace|--execute <workload> or <workload> <compile|execute> [benchmark flags]");
        bool verify = std::string(argv[1]) == "--trace" || std::string(argv[1]) == "--execute";
        Inputs inputs = read_inputs(argv[verify ? 2 : 1]);
        Engine::check(inputs);
        std::cerr << "phase=compile\n";
        auto pattern = Engine::compile(inputs);
        if (!pattern.ok()) throw std::runtime_error(pattern.error());
        auto sources = inputs.sources();
        auto groups = Engine::groups(inputs, pattern);
        if (verify) {
            if (inputs.model == "compile") std::cout << "compiled\n";
            else {
                std::cerr << "phase=execution\n";
                bool trace = std::string(argv[1]) == "--trace";
                int64_t result = Engine::execute(inputs, pattern, sources, groups, trace);
                if (!trace) std::cout << result << '\n';
            }
            return 0;
        }
        std::string operation = argv[2];
        if (operation != (inputs.model == "compile" ? "compile" : "execute")) throw std::runtime_error("operation/model mismatch");
        benchmark::RegisterBenchmark(operation.c_str(), [&](benchmark::State& state) {
            if (operation == "compile") {
                for (auto _ : state) {
                    auto compiled = Engine::compile(inputs);
                    benchmark::DoNotOptimize(compiled);
                }
            }
            else {
                for (auto _ : state) benchmark::DoNotOptimize(Engine::execute(inputs, pattern, sources, groups, false));
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
} // namespace
