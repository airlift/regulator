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
// compile (returning a pattern with ok() and error()), contains, find, which returns
// the first match's match_bounds or kNoMatch, count with an optional match trace,
// and check, which rejects inputs the engine cannot accept.
//
// For the baseline's specialized-pattern comparators an adapter also supplies the
// nearest public form of each specialized operation: extract, the given group of
// the first match, and extract_all, that group of every match, over per-pattern
// scratch state from scratch(pattern, group), and check_specialized(operation,
// group), which rejects a group the operation cannot name. An adapter whose library
// has a global replacement also supplies replace, which substitutes the group for
// every match, and sets replaces. Each takes an optional trace of the values it returns.

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

// What find returns when the input has no match.
constexpr int64_t kNoMatch = -1;

// A match's start and end byte offsets in one value, the start in the high 32 bits and the end in
// the low, as BenchmarkLanguageComparison.bounds packs them.
int64_t match_bounds(size_t start, size_t end) {
    return static_cast<int64_t>(start) << 32 | static_cast<int64_t>(end);
}

// Whether find's bounds hold the bytes of the first of the trace's matches, or no match when it has none.
bool holds_first_match(std::string_view input, int64_t bounds, const std::vector<std::string>& matches) {
    if (bounds == kNoMatch) return matches.empty();
    size_t start = static_cast<size_t>(bounds >> 32);
    size_t end = static_cast<size_t>(bounds & 0xffffffff);
    return !matches.empty() && start <= end && end <= input.size() &&
            encode_hex(input.substr(start, end - start)) == matches.front();
}

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
        if (result != source.expected || Engine::contains(pattern, source.bytes()) != (result != 0) ||
                !holds_first_match(source.bytes(), Engine::find(pattern, source.bytes()), matches)) {
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

enum class Operation { Compile, SingleContains, SingleCount, ReusedContains, ReusedCount, ReusedFind };

bool is_operation(const std::string& operation) {
    return operation == "compile" || operation == "singleUseContains" || operation == "singleUseCount" ||
            operation == "reusedContains" || operation == "reusedCount" || operation == "reusedFind";
}

template <typename Engine>
void verify_operation(const Inputs& inputs, const std::string& operation) {
    if (!is_operation(operation)) throw std::runtime_error("unknown operation");
    std::cerr << "phase=compile\n";
    auto pattern = Engine::compile(inputs.pattern);
    if (!pattern.ok()) throw std::runtime_error(pattern.error());
    if (operation == "compile") {
        std::cout << "compiled\n";
        return;
    }
    std::cerr << "phase=execution\n";
    bool is_contains = operation == "singleUseContains" || operation == "reusedContains";
    std::vector<int64_t> first_pass(inputs.sources.size());
    for (int pass = 0; pass < 2; ++pass) {
        for (size_t index = 0; index < inputs.sources.size(); ++index) {
            const Source& source = inputs.sources[index];
            int64_t result;
            if (operation == "singleUseContains" || operation == "singleUseCount") {
                auto compiled = Engine::compile(inputs.pattern);
                if (!compiled.ok()) throw std::runtime_error(compiled.error());
                result = is_contains ? Engine::contains(compiled, source.bytes()) : Engine::count(compiled, source.bytes());
            }
            else if (operation == "reusedFind") {
                result = Engine::find(pattern, source.bytes());
            }
            else {
                result = is_contains ? Engine::contains(pattern, source.bytes()) : Engine::count(pattern, source.bytes());
            }
            // The inputs record counts but no expected first-match bounds, so collection compares the first pass's
            // bounds across engines and with the trace. The second pass must repeat the first exactly, so reused state
            // that goes wrong once the rotation wraps is rejected too.
            bool verified = operation == "reusedFind" ? (result == kNoMatch) == (source.expected == 0)
                    : result == (is_contains ? (source.expected != 0) : source.expected);
            if (!verified || (pass > 0 && result != first_pass[index])) {
                throw std::runtime_error("native operation verification failed");
            }
            if (pass == 0) {
                first_pass[index] = result;
                std::cout << result << '\n';
            }
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
            else if constexpr (operation == Operation::ReusedFind) {
                benchmark::DoNotOptimize(Engine::find(pattern, source));
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

enum class Specialized { Replace, Extract, ExtractAll };

int capture_group(const std::string& text) {
    try {
        size_t length = 0;
        int group = std::stoi(text, &length);
        if (length == text.size() && group >= 1) return group;
    }
    catch (const std::logic_error&) {
        // Not a number, or out of range for int; reported below.
    }
    throw std::runtime_error("invalid capture group: " + text);
}

template <typename Engine, Specialized operation, typename Pattern, typename Scratch>
void run_specialized(const Pattern& pattern, Scratch& scratch, std::string_view source, int group,
        std::vector<std::string>* trace) {
    if constexpr (operation == Specialized::Replace) {
        benchmark::DoNotOptimize(Engine::replace(pattern, scratch, source, trace));
    }
    else if constexpr (operation == Specialized::Extract) {
        benchmark::DoNotOptimize(Engine::extract(pattern, scratch, source, group, trace));
    }
    else {
        benchmark::DoNotOptimize(Engine::extract_all(pattern, scratch, source, group, trace));
    }
}

// Prints, for each input, the number of values the operation returned and each value in hex, with - for a group
// that did not participate in an extract-all match (scalar extract never prints -), in the format
// SpecializedPatternNativeInputs writes for the Regulator rows.
template <typename Engine, Specialized operation>
void verify_specialized(const Inputs& inputs, int group) {
    auto pattern = Engine::compile(inputs.pattern);
    if (!pattern.ok()) throw std::runtime_error(pattern.error());
    auto scratch = Engine::scratch(pattern, group);
    for (const Source& source : inputs.sources) {
        std::vector<std::string> values;
        run_specialized<Engine, operation>(pattern, scratch, source.bytes(), group, &values);
        std::cout << values.size() << ':';
        for (size_t i = 0; i < values.size(); ++i) {
            if (i != 0) std::cout << ',';
            std::cout << values[i];
        }
        std::cout << '\n';
    }
}

template <typename Engine, Specialized operation>
void measure_specialized(benchmark::State& state, const Inputs& inputs, int group) {
    auto pattern = Engine::compile(inputs.pattern);
    if (!pattern.ok()) { state.SkipWithError(pattern.error().c_str()); return; }
    auto scratch = Engine::scratch(pattern, group);
    size_t index = 0;
    for (auto _ : state) {
        std::string_view source = inputs.sources[index].bytes();
        index = (index + 1) % inputs.sources.size();
        run_specialized<Engine, operation>(pattern, scratch, source, group, nullptr);
    }
}

// Verifies the operation's values, or registers it to be timed under its name.
template <typename Engine, Specialized operation>
void specialized_operation(const Inputs& inputs, const std::string& name, int group, bool verify) {
    Engine::check_specialized(operation, group);
    if (verify) {
        verify_specialized<Engine, operation>(inputs, group);
        return;
    }
    benchmark::RegisterBenchmark(name.c_str(), [&inputs, group](benchmark::State& state) {
        measure_specialized<Engine, operation>(state, inputs, group);
    });
}

template <typename Engine>
void specialized(const Inputs& inputs, const std::string& name, int group, bool verify) {
    if (name == "extract") {
        specialized_operation<Engine, Specialized::Extract>(inputs, name, group, verify);
        return;
    }
    if (name == "extractAll") {
        specialized_operation<Engine, Specialized::ExtractAll>(inputs, name, group, verify);
        return;
    }
    if constexpr (Engine::replaces) {
        if (name == "replace") {
            specialized_operation<Engine, Specialized::Replace>(inputs, name, group, verify);
            return;
        }
    }
    throw std::runtime_error("unknown specialized operation: " + name);
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
        if (argc == 5 && std::string(argv[1]) == "--verify-specialized") {
            Inputs inputs = read_inputs(argv[2]);
            Engine::check(inputs);
            specialized<Engine>(inputs, argv[3], capture_group(argv[4]), true);
            return 0;
        }
        // Times without verifying, because run-shard.sh runs the --verify-specialized gate first.
        if (argc >= 5 && std::string(argv[1]) == "--specialized") {
            Inputs inputs = read_inputs(argv[2]);
            Engine::check(inputs);
            specialized<Engine>(inputs, argv[3], capture_group(argv[4]), false);
            for (int i = 5; i < argc; ++i) argv[i - 4] = argv[i];
            argc -= 4;
            benchmark::Initialize(&argc, argv);
            if (benchmark::ReportUnrecognizedArguments(argc, argv)) return 1;
            benchmark::RunSpecifiedBenchmarks();
            benchmark::Shutdown();
            return 0;
        }
        if (argc < 3) {
            throw std::runtime_error("usage: " + program + " <workload> <operation> [benchmark flags]"
                    " | --specialized <workload> <operation> <group> [benchmark flags]"
                    " | --verify-specialized <workload> <operation> <group>");
        }
        Inputs inputs = read_inputs(argv[1]);
        Engine::check(inputs);
        std::string operation = argv[2];
        if (!is_operation(operation)) throw std::runtime_error("unknown operation");
        // Check only the selected operation here; full match traces are collected separately.
        verify_operation<Engine>(inputs, operation);
        if (operation == "compile") register_operation<Engine, Operation::Compile>(operation, inputs);
        if (operation == "singleUseContains") register_operation<Engine, Operation::SingleContains>(operation, inputs);
        if (operation == "singleUseCount") register_operation<Engine, Operation::SingleCount>(operation, inputs);
        if (operation == "reusedContains") register_operation<Engine, Operation::ReusedContains>(operation, inputs);
        if (operation == "reusedCount") register_operation<Engine, Operation::ReusedCount>(operation, inputs);
        if (operation == "reusedFind") register_operation<Engine, Operation::ReusedFind>(operation, inputs);
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
