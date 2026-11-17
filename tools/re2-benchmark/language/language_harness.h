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
// Timing follows the pass protocol: one benchmark iteration is one in-order pass over every input of the
// workload, each result sunk by DoNotOptimize as it is returned, and the iteration reports the number of inputs
// as the inputs_per_iteration counter, so a result's time per operation is its time divided by that count.
// Compilation has no inputs: one iteration is one compile and reports no counter.
//
// A runner also supplies the length function of its null engine, NullEngine<Length>, whose every operation returns
// the input's byte length through the boundary the runner's engine is reached by. Timed by the same pass, its
// time per input is the harness's own overhead.
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
            // that goes wrong on the second pass is rejected too.
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

// The inputs in workload order, as the views each timed pass reads.
std::vector<std::string_view> input_views(const Inputs& inputs) {
    std::vector<std::string_view> views;
    views.reserve(inputs.sources.size());
    for (const Source& source : inputs.sources) views.push_back(source.bytes());
    return views;
}

template <typename Engine, Operation operation>
void measure(benchmark::State& state, const Inputs& inputs) {
    auto pattern = Engine::compile(inputs.pattern);
    if (!pattern.ok()) { state.SkipWithError(pattern.error().c_str()); return; }
    if constexpr (operation == Operation::Compile) {
        for (auto _ : state) {
            auto compiled = Engine::compile(inputs.pattern);
            benchmark::DoNotOptimize(compiled);
        }
    }
    else {
        const std::vector<std::string_view> views = input_views(inputs);
        const std::string_view* const sources = views.data();
        const size_t count = views.size();
        for (auto _ : state) {
            for (size_t index = 0; index < count; ++index) {
                std::string_view source = sources[index];
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
        state.counters["inputs_per_iteration"] = static_cast<double>(count);
    }
}

template <typename Engine, Operation operation>
void register_operation(const std::string& name, const Inputs& inputs) {
    benchmark::RegisterBenchmark(name.c_str(), [&inputs](benchmark::State& state) {
        measure<Engine, operation>(state, inputs);
    });
}

template <typename Engine>
void register_lifecycle(const std::string& operation, const Inputs& inputs) {
    if (operation == "compile") register_operation<Engine, Operation::Compile>(operation, inputs);
    if (operation == "singleUseContains") register_operation<Engine, Operation::SingleContains>(operation, inputs);
    if (operation == "singleUseCount") register_operation<Engine, Operation::SingleCount>(operation, inputs);
    if (operation == "reusedContains") register_operation<Engine, Operation::ReusedContains>(operation, inputs);
    if (operation == "reusedCount") register_operation<Engine, Operation::ReusedCount>(operation, inputs);
    if (operation == "reusedFind") register_operation<Engine, Operation::ReusedFind>(operation, inputs);
}

// A null engine over Length::length, which returns an input's byte length through the boundary of the runner's
// engine. Every operation returns that length, and it has no compiled state, so measure times only the pass.
template <typename Length>
struct NullEngine {
    struct Pattern {
        bool ok() const { return true; }
        std::string error() const { return ""; }
    };

    static Pattern compile(const std::string&) { return {}; }
    static size_t contains(const Pattern&, std::string_view input) { return Length::length(input); }
    static size_t count(const Pattern&, std::string_view input) { return Length::length(input); }
    static size_t find(const Pattern&, std::string_view input) { return Length::length(input); }
};

// The null engine runs only the reused operations, the harness's overhead around one call per input.
bool is_null_operation(const std::string& operation) {
    return operation == "reusedContains" || operation == "reusedCount" || operation == "reusedFind";
}

// Prints the null engine's result for each input, one line per input, after checking that each of two passes
// returns every input's byte length.
template <typename Null>
void verify_null(const Inputs& inputs, const std::string& operation) {
    if (!is_null_operation(operation)) throw std::runtime_error("the null engine has only reused operations");
    auto pattern = Null::compile(inputs.pattern);
    std::cerr << "phase=execution\n";
    for (int pass = 0; pass < 2; ++pass) {
        for (const Source& source : inputs.sources) {
            size_t result = operation == "reusedContains" ? Null::contains(pattern, source.bytes())
                    : operation == "reusedCount" ? Null::count(pattern, source.bytes()) : Null::find(pattern, source.bytes());
            if (result != source.bytes().size()) throw std::runtime_error("null verification failed");
            if (pass == 0) std::cout << result << '\n';
        }
    }
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
    const std::vector<std::string_view> views = input_views(inputs);
    const std::string_view* const sources = views.data();
    const size_t count = views.size();
    for (auto _ : state) {
        for (size_t index = 0; index < count; ++index) {
            run_specialized<Engine, operation>(pattern, scratch, sources[index], group, nullptr);
        }
    }
    state.counters["inputs_per_iteration"] = static_cast<double>(count);
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

// Runs the benchmark library over the flags from argv[first] on, behind the program name.
int run_benchmarks(int argc, char** argv, int first) {
    for (int i = first; i < argc; ++i) argv[i - first + 1] = argv[i];
    argc -= first - 1;
    benchmark::Initialize(&argc, argv);
    if (benchmark::ReportUnrecognizedArguments(argc, argv)) return 1;
    benchmark::RunSpecifiedBenchmarks();
    benchmark::Shutdown();
    return 0;
}

template <typename Engine, typename Length>
int run(int argc, char** argv, const std::string& program) {
    using Null = NullEngine<Length>;
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
            return run_benchmarks(argc, argv, 5);
        }
        if (argc == 4 && std::string(argv[1]) == "--verify-null") {
            Inputs inputs = read_inputs(argv[2]);
            Engine::check(inputs);
            verify_null<Null>(inputs, argv[3]);
            return 0;
        }
        if (argc >= 4 && std::string(argv[1]) == "--null") {
            Inputs inputs = read_inputs(argv[2]);
            Engine::check(inputs);
            std::string operation = argv[3];
            verify_null<Null>(inputs, operation);
            register_lifecycle<Null>(operation, inputs);
            return run_benchmarks(argc, argv, 4);
        }
        if (argc < 3) {
            throw std::runtime_error("usage: " + program + " <workload> <operation> [benchmark flags]"
                    " | --specialized <workload> <operation> <group> [benchmark flags]"
                    " | --verify-specialized <workload> <operation> <group>"
                    " | --null <workload> <operation> [benchmark flags] | --verify-null <workload> <operation>");
        }
        Inputs inputs = read_inputs(argv[1]);
        Engine::check(inputs);
        std::string operation = argv[2];
        if (!is_operation(operation)) throw std::runtime_error("unknown operation");
        // Check only the selected operation here; full match traces are collected separately.
        verify_operation<Engine>(inputs, operation);
        register_lifecycle<Engine>(operation, inputs);
        return run_benchmarks(argc, argv, 3);
    }
    catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
} // namespace
