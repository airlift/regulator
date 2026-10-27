cmake_minimum_required(VERSION 3.20)
project(re2_benchmark_dependencies LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(ABSL_PROPAGATE_CXX_STD ON)
set(BENCHMARK_ENABLE_TESTING OFF)
set(BENCHMARK_ENABLE_INSTALL OFF)
set(RE2_INSTALL OFF)

add_subdirectory("${ABSL_DIR}" abseil-cpp)
add_subdirectory("${BENCHMARK_DIR}" benchmark)
add_subdirectory("${RE2_DIR}" re2)

add_executable(language_benchmark language/language_benchmark.cc)
target_link_libraries(language_benchmark PRIVATE re2::re2 benchmark::benchmark)

add_executable(language_bulk_benchmark language/language_bulk_benchmark.cc)
target_link_libraries(language_bulk_benchmark PRIVATE re2::re2 benchmark::benchmark)

# The Rust comparator is the pinned regex crate as a static library, built by
# Cargo from its lock file with no flags beyond its release profile.
find_program(CARGO_EXECUTABLE cargo)
if(CARGO_EXECUTABLE)
    get_filename_component(CARGO_DIRECTORY "${CARGO_EXECUTABLE}" DIRECTORY)
    find_program(RUSTC_EXECUTABLE rustc HINTS "${CARGO_DIRECTORY}" NO_DEFAULT_PATH)
    set(RUST_REGEX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/language/rust-regex")
    set(RUST_REGEX_TARGET_DIR "${CMAKE_BINARY_DIR}/rust-regex")
    set(RUST_REGEX_LIBRARY "${RUST_REGEX_TARGET_DIR}/release/${CMAKE_STATIC_LIBRARY_PREFIX}language_rust_regex${CMAKE_STATIC_LIBRARY_SUFFIX}")
    # Cargo runs on every build and decides itself whether the library is fresh,
    # after a check that rejects any environment or configuration that would
    # change its release profile. Run from the crate so rustup selects its
    # pinned toolchain. Cargo restores a cached library with its old timestamp,
    # so touching it makes every build relink the runners against the library
    # Cargo just chose.
    add_custom_target(language_rust_regex
        COMMAND python3 "${CMAKE_CURRENT_SOURCE_DIR}/language/native_build.py" --check-cargo-environment
        COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${RUST_REGEX_TARGET_DIR}" "RUSTC=${RUSTC_EXECUTABLE}"
                "CARGO_ENCODED_RUSTFLAGS=" "${CARGO_EXECUTABLE}" build --release --locked
        COMMAND "${CMAKE_COMMAND}" -E touch_nocreate "${RUST_REGEX_LIBRARY}"
        BYPRODUCTS "${RUST_REGEX_LIBRARY}"
        WORKING_DIRECTORY "${RUST_REGEX_DIR}"
        VERBATIM)
    # The system libraries rustc reports for the crate's standard library. On
    # macOS they are all libSystem, which the compiler driver already links.
    if(NOT APPLE)
        set(RUST_REGEX_SYSTEM_LIBRARIES gcc_s util rt pthread m dl c)
    endif()

    foreach(target language_rust_benchmark language_rust_bulk_benchmark)
        add_executable(${target} language/${target}.cc)
        add_dependencies(${target} language_rust_regex)
        target_link_libraries(${target} PRIVATE "${RUST_REGEX_LIBRARY}" benchmark::benchmark ${RUST_REGEX_SYSTEM_LIBRARIES})
    endforeach()
endif()
