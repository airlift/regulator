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

// Lifecycle harness shared by the native comparators. An engine adapter supplies
// compile (returning a pattern with ok() and error()), contains, count with an
// optional match trace, and check, which rejects inputs the engine cannot accept.

#include <benchmark/benchmark.h>

#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {
struct Source {
    std::string storage;
    size_t offset;
    int64_t expected;

    std::string_view bytes() const { return std::string_view(storage).substr(offset); }
};

std::string decode_hex(const std::string& text) {
    if (text.size() % 2 != 0) throw std::runtime_error("odd hex input");
    auto digit = [](char c) -> unsigned {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        throw std::runtime_error("invalid hex input");
    };
    std::string result;
    for (size_t i = 0; i < text.size(); i += 2) {
        result.push_back(static_cast<char>(digit(text[i]) * 16 + digit(text[i + 1])));
    }
    return result;
}

std::string encode_hex(std::string_view value) {
    const char* digits = "0123456789abcdef";
    std::string result;
    for (unsigned char c : value) {
        result += digits[c >> 4];
        result += digits[c & 15];
    }
    return result;
}

struct Inputs {
    std::string pattern;
    std::vector<Source> sources;
};

Inputs read_inputs(const std::string& path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open workload");
    std::string line;
    if (!std::getline(input, line)) throw std::runtime_error("missing pattern");
    Inputs inputs{decode_hex(line), {}};
    while (std::getline(input, line)) {
        size_t first = line.find('\t');
        size_t second = line.find('\t', first + 1);
        if (first == std::string::npos || second == std::string::npos) {
            throw std::runtime_error("expected offset, count, and input");
        }
        int offset = std::stoi(line.substr(0, first));
        int64_t count = std::stoll(line.substr(first + 1, second - first - 1));
        if (offset < 0 || count < 0) throw std::runtime_error("negative offset or count");
        inputs.sources.push_back({std::string(offset, '\0') + decode_hex(line.substr(second + 1)),
                static_cast<size_t>(offset), count});
    }
    if (inputs.sources.empty()) throw std::runtime_error("missing inputs");
    return inputs;
}

template <typename Engine>
void verify(const Inputs& inputs) {
    std::cerr << "phase=compile\n";
    auto pattern = Engine::compile(inputs.pattern);
    if (!pattern.ok()) throw std::runtime_error(pattern.error());
    std::cerr << "phase=execution\n";
    for (const Source& source : inputs.sources) {
        std::vector<std::string> matches;
        int64_t result = Engine::count(pattern, source.bytes(), &matches);
        if (result != source.expected || Engine::contains(pattern, source.bytes()) != (result != 0)) {
            throw std::runtime_error("native verification failed");
        }
        std::cout << result << ':';
        for (size_t i = 0; i < matches.size(); ++i) {
            if (i != 0) std::cout << ',';
            std::cout << matches[i];
        }
        std::cout << '\n';
    }
}

enum class Operation { Compile, SingleContains, SingleCount, ReusedContains, ReusedCount };

template <typename Engine>
void verify_operation(const Inputs& inputs, const std::string& operation) {
    if (operation != "compile" && operation != "singleUseContains" && operation != "singleUseCount" &&
            operation != "reusedContains" && operation != "reusedCount") {
        throw std::runtime_error("unknown operation");
    }
    std::cerr << "phase=compile\n";
    auto pattern = Engine::compile(inputs.pattern);
    if (!pattern.ok()) throw std::runtime_error(pattern.error());
    if (operation == "compile") {
        std::cout << "compiled\n";
        return;
    }
    std::cerr << "phase=execution\n";
    bool is_contains = operation == "singleUseContains" || operation == "reusedContains";
    for (int pass = 0; pass < 2; ++pass) {
        for (const Source& source : inputs.sources) {
            int64_t result;
            if (operation == "singleUseContains" || operation == "singleUseCount") {
                auto compiled = Engine::compile(inputs.pattern);
                if (!compiled.ok()) throw std::runtime_error(compiled.error());
                result = is_contains ? Engine::contains(compiled, source.bytes()) : Engine::count(compiled, source.bytes());
            }
            else {
                result = is_contains ? Engine::contains(pattern, source.bytes()) : Engine::count(pattern, source.bytes());
            }
            if (result != (is_contains ? (source.expected != 0) : source.expected)) {
                throw std::runtime_error("native operation verification failed");
            }
            if (pass == 0) std::cout << result << '\n';
        }
    }
}

template <typename Engine, Operation operation>
void measure(benchmark::State& state, const Inputs& inputs) {
    auto pattern = Engine::compile(inputs.pattern);
    if (!pattern.ok()) { state.SkipWithError(pattern.error().c_str()); return; }
    size_t index = 0;
    for (auto _ : state) {
        if constexpr (operation == Operation::Compile) {
            auto compiled = Engine::compile(inputs.pattern);
            benchmark::DoNotOptimize(compiled);
        }
        else {
            std::string_view source = inputs.sources[index].bytes();
            index = (index + 1) % inputs.sources.size();
            if constexpr (operation == Operation::SingleContains) {
                auto compiled = Engine::compile(inputs.pattern);
                benchmark::DoNotOptimize(Engine::contains(compiled, source));
            }
            else if constexpr (operation == Operation::SingleCount) {
                auto compiled = Engine::compile(inputs.pattern);
                benchmark::DoNotOptimize(Engine::count(compiled, source));
            }
            else if constexpr (operation == Operation::ReusedContains) {
                benchmark::DoNotOptimize(Engine::contains(pattern, source));
            }
            else if constexpr (operation == Operation::ReusedCount) {
                benchmark::DoNotOptimize(Engine::count(pattern, source));
            }
        }
    }
}

template <typename Engine, Operation operation>
void register_operation(const std::string& name, const Inputs& inputs) {
    benchmark::RegisterBenchmark(name.c_str(), [&inputs](benchmark::State& state) {
        measure<Engine, operation>(state, inputs);
    });
}

template <typename Engine>
int run(int argc, char** argv, const std::string& program) {
    try {
        if (argc == 3 && std::string(argv[1]) == "--verify") {
            Inputs inputs = read_inputs(argv[2]);
            Engine::check(inputs);
            verify<Engine>(inputs);
            return 0;
        }
        if (argc == 4 && std::string(argv[1]) == "--verify-operation") {
            Inputs inputs = read_inputs(argv[2]);
            Engine::check(inputs);
            verify_operation<Engine>(inputs, argv[3]);
            return 0;
        }
        if (argc < 3) throw std::runtime_error("usage: " + program + " <workload> <operation> [benchmark flags]");
        Inputs inputs = read_inputs(argv[1]);
        Engine::check(inputs);
        std::string operation = argv[2];
        if (operation != "compile" && operation != "singleUseContains" && operation != "singleUseCount" &&
                operation != "reusedContains" && operation != "reusedCount") {
            throw std::runtime_error("unknown operation");
        }
        // Check only the selected operation here; full match traces are collected separately.
        verify_operation<Engine>(inputs, operation);
        if (operation == "compile") register_operation<Engine, Operation::Compile>(operation, inputs);
        if (operation == "singleUseContains") register_operation<Engine, Operation::SingleContains>(operation, inputs);
        if (operation == "singleUseCount") register_operation<Engine, Operation::SingleCount>(operation, inputs);
        if (operation == "reusedContains") register_operation<Engine, Operation::ReusedContains>(operation, inputs);
        if (operation == "reusedCount") register_operation<Engine, Operation::ReusedCount>(operation, inputs);
        for (int i = 3; i < argc; ++i) argv[i - 2] = argv[i];
        argc -= 2;
        benchmark::Initialize(&argc, argv);
        if (benchmark::ReportUnrecognizedArguments(argc, argv)) return 1;
        benchmark::RunSpecifiedBenchmarks();
        benchmark::Shutdown();
        return 0;
    }
    catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
} // namespace
