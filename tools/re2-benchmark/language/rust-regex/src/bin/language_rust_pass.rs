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

//! Times the pinned `regex` crate from a Rust loop, with no C interface between the
//! loop and the crate, so each search is inlined as it is for a Rust caller.
//!
//! It follows the lifecycle runner's pass protocol (language_harness.h): one pass is an
//! in-order walk over every input of the workload, each result sunk by `black_box` as it
//! is returned, and the inputs and compiled regex pass through `black_box` once per pass
//! so nothing is carried from one pass to the next. The regex compiles with the builder
//! settings the C++ runner's Rust engine passes the shim for lifecycle workloads, and
//! each operation is the shim's call.
//!
//! Usage:
//!   language_rust_pass <workload> <operation> <warmup_seconds> <repetitions> <min_seconds>
//!   language_rust_pass --verify <workload> <operation>
//! where operation is reusedContains, reusedCount, reusedFind, or null.

use std::hint::black_box;
use std::process::ExitCode;
use std::time::{Duration, Instant};

use regex::{Regex, RegexBuilder};

/// What find returns when the input has no match, as the C++ runner's `kNoMatch`.
const NO_MATCH: i64 = -1;

struct Source {
    storage: String,
    offset: usize,
    expected: i64,
}

impl Source {
    fn text(&self) -> &str {
        &self.storage[self.offset..]
    }
}

struct Inputs {
    pattern: String,
    sources: Vec<Source>,
}

fn decode_hex(text: &str, result: &mut Vec<u8>) -> Result<(), String> {
    let text = text.as_bytes();
    if text.len() % 2 != 0 {
        return Err("odd hex input".into());
    }
    let digit = |c: u8| -> Result<u8, String> {
        match c {
            b'0'..=b'9' => Ok(c - b'0'),
            b'a'..=b'f' => Ok(c - b'a' + 10),
            _ => Err("invalid hex input".into()),
        }
    };
    for pair in text.chunks_exact(2) {
        result.push(digit(pair[0])? * 16 + digit(pair[1])?);
    }
    Ok(())
}

/// Reads the workload as language_harness.h `read_inputs` does: the hex pattern, then one
/// `offset\tcount\thex` line per input, whose text follows `offset` zero bytes in its storage.
/// Every input is checked as UTF-8 here, before any timing, as the C++ Rust engine's check does.
fn read_inputs(path: &str) -> Result<Inputs, String> {
    let contents = std::fs::read_to_string(path).map_err(|_| "cannot open workload".to_string())?;
    let mut lines = contents.split_terminator('\n');
    let mut pattern = Vec::new();
    decode_hex(lines.next().ok_or("missing pattern")?, &mut pattern)?;
    let pattern = String::from_utf8(pattern).map_err(|_| "pattern is not UTF-8")?;
    let mut sources = Vec::new();
    for line in lines {
        let mut fields = line.splitn(3, '\t');
        let (Some(offset), Some(count), Some(hex)) = (fields.next(), fields.next(), fields.next()) else {
            return Err("expected offset, count, and input".into());
        };
        let offset: usize = offset.parse().map_err(|_| "negative offset or count")?;
        let expected: i64 = count.parse().map_err(|_| "negative offset or count")?;
        if expected < 0 {
            return Err("negative offset or count".into());
        }
        let mut storage = Vec::with_capacity(offset + hex.len() / 2);
        storage.resize(offset, 0);
        decode_hex(hex, &mut storage)?;
        // The leading zero bytes are ASCII, so the input starts on a character boundary.
        let storage = String::from_utf8(storage).map_err(|_| "input is not UTF-8")?;
        sources.push(Source { storage, offset, expected });
    }
    if sources.is_empty() {
        return Err("missing inputs".into());
    }
    Ok(Inputs { pattern, sources })
}

/// The shim's compile for the C++ Rust engine's lifecycle workloads: Unicode mode, case-sensitive,
/// and no memory limit, so the crate's default compiled-size and lazy DFA cache limits.
fn compile(pattern: &str) -> Result<Regex, String> {
    RegexBuilder::new(pattern).unicode(true).case_insensitive(false).build().map_err(|failure| failure.to_string())
}

/// One timed operation: the shim's call for the operation, and how the C++ runner checks its result.
trait Operation {
    type Output: Copy;

    fn run(regex: &Regex, input: &str) -> Self::Output;

    fn value(output: Self::Output) -> i64;

    fn verified(result: i64, source: &Source) -> bool;
}

struct Contains;
struct Count;
struct Find;
/// The runner's null engine, the input's length in bytes, borrowed as `&str` as every search
/// borrows it, so its time per input is this loop's own overhead.
struct Null;

impl Operation for Contains {
    type Output = bool;

    #[inline(always)]
    fn run(regex: &Regex, input: &str) -> bool {
        regex.is_match(input)
    }

    fn value(output: bool) -> i64 {
        output as i64
    }

    fn verified(result: i64, source: &Source) -> bool {
        result == (source.expected != 0) as i64
    }
}

impl Operation for Count {
    type Output = u64;

    #[inline(always)]
    fn run(regex: &Regex, input: &str) -> u64 {
        regex.find_iter(input).count() as u64
    }

    fn value(output: u64) -> i64 {
        output as i64
    }

    fn verified(result: i64, source: &Source) -> bool {
        result == source.expected
    }
}

impl Operation for Find {
    type Output = i64;

    /// The first match's start and end byte offsets packed as the C++ runner's `match_bounds`.
    #[inline(always)]
    fn run(regex: &Regex, input: &str) -> i64 {
        match regex.find(input) {
            Some(found) => (found.start() as i64) << 32 | found.end() as i64,
            None => NO_MATCH,
        }
    }

    fn value(output: i64) -> i64 {
        output
    }

    fn verified(result: i64, source: &Source) -> bool {
        (result == NO_MATCH) == (source.expected == 0)
    }
}

impl Operation for Null {
    type Output = usize;

    #[inline(always)]
    fn run(_: &Regex, input: &str) -> usize {
        input.len()
    }

    fn value(output: usize) -> i64 {
        output as i64
    }

    fn verified(result: i64, source: &Source) -> bool {
        result == source.text().len() as i64
    }
}

/// Runs `passes` passes over the inputs. Kept out of line so the timed loop is one symbol to read
/// in a disassembly; the operation inlines into it.
#[inline(never)]
fn run_passes<O: Operation>(regex: &Regex, views: &[&str], passes: u64) {
    for _ in 0..passes {
        let regex = black_box(regex);
        let views = black_box(views);
        for &input in views {
            black_box(O::run(regex, input));
        }
    }
}

/// Runs two passes and checks every result as the C++ runner's `--verify-operation` (or, for the
/// null engine, `--verify-null`) does, returning the first pass's results.
fn verify<O: Operation>(regex: &Regex, inputs: &Inputs) -> Result<Vec<i64>, String> {
    let mut first_pass = Vec::with_capacity(inputs.sources.len());
    for pass in 0..2 {
        for (index, source) in inputs.sources.iter().enumerate() {
            let result = O::value(O::run(regex, source.text()));
            if !O::verified(result, source) || (pass > 0 && result != first_pass[index]) {
                return Err("native operation verification failed".into());
            }
            if pass == 0 {
                first_pass.push(result);
            }
        }
    }
    Ok(first_pass)
}

/// Times whole passes the way the benchmark library sizes an iteration count: run a batch, and while
/// it took less than `minimum`, grow the batch toward what should take 1.4 times `minimum`, at most
/// tenfold. Returns the final batch's passes and elapsed time, read only around each batch.
fn measure<O: Operation>(regex: &Regex, views: &[&str], minimum: Duration) -> (u64, Duration) {
    let mut passes = 1u64;
    loop {
        let start = Instant::now();
        run_passes::<O>(regex, views, passes);
        let elapsed = start.elapsed();
        if elapsed >= minimum {
            return (passes, elapsed);
        }
        let multiplier = (minimum.as_secs_f64() * 1.4 / elapsed.as_secs_f64().max(1e-9)).min(10.0);
        passes = (passes + 1).max((passes as f64 * multiplier).ceil() as u64);
    }
}

fn json_string(value: &str) -> String {
    let mut result = String::from("\"");
    for c in value.chars() {
        match c {
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            c if (c as u32) < 0x20 => result.push_str(&format!("\\u{:04x}", c as u32)),
            c => result.push(c),
        }
    }
    result.push('"');
    result
}

/// The pinned crate's version, from the lock file the binary was built from.
fn regex_version() -> Option<&'static str> {
    let lock = include_str!("../../Cargo.lock");
    let rest = &lock[lock.find("name = \"regex\"\nversion = \"")? + "name = \"regex\"\nversion = \"".len()..];
    Some(&rest[..rest.find('"')?])
}

/// The compiler that built this binary, from the `rustc version` note rustc leaves in its `.comment` section.
fn rustc_version() -> Option<String> {
    let image = std::fs::read(std::env::current_exe().ok()?).ok()?;
    let marker = b"rustc version ";
    let start = image.windows(marker.len()).position(|window| window == marker)?;
    let end = start + image[start..].iter().position(|&byte| byte == 0)?;
    Some(String::from_utf8_lossy(&image[start..end]).into_owned())
}

fn seconds(text: &str, name: &str) -> Result<Duration, String> {
    let value: f64 = text.parse().map_err(|_| format!("invalid {name}: {text}"))?;
    Duration::try_from_secs_f64(value).map_err(|_| format!("invalid {name}: {text}"))
}

fn run_operation<O: Operation>(arguments: &[String], inputs: &Inputs) -> Result<(), String> {
    eprintln!("phase=compile");
    let regex = compile(&inputs.pattern)?;
    eprintln!("phase=execution");
    let results = verify::<O>(&regex, inputs)?;
    if arguments[1] == "--verify" {
        for result in results {
            println!("{result}");
        }
        return Ok(());
    }
    let (workload, operation) = (&arguments[1], &arguments[2]);
    let warmup = seconds(&arguments[3], "warmup seconds")?;
    let repetitions: u32 = arguments[4].parse().map_err(|_| format!("invalid repetitions: {}", arguments[4]))?;
    let minimum = seconds(&arguments[5], "minimum seconds")?;
    let views: Vec<&str> = inputs.sources.iter().map(Source::text).collect();
    println!(
        "{{\"binary\":\"language_rust_pass\",\"regex\":{},\"rustc\":{},\"workload\":{},\"operation\":{},\"inputs\":{},\
         \"warmup_seconds\":{},\"repetitions\":{},\"min_seconds\":{}}}",
        regex_version().map_or("null".into(), json_string),
        rustc_version().map_or("null".into(), |version| json_string(&version)),
        json_string(workload),
        json_string(operation),
        views.len(),
        warmup.as_secs_f64(),
        repetitions,
        minimum.as_secs_f64()
    );
    let start = Instant::now();
    while start.elapsed() < warmup {
        run_passes::<O>(&regex, &views, 1);
    }
    for repetition in 1..=repetitions {
        let (passes, elapsed) = measure::<O>(&regex, &views, minimum);
        let nanos = elapsed.as_nanos();
        println!(
            "{{\"repetition\":{repetition},\"passes\":{passes},\"inputs\":{},\"elapsed_ns\":{nanos},\"ns_per_input\":{}}}",
            views.len(),
            nanos as f64 / (passes as f64 * views.len() as f64)
        );
    }
    Ok(())
}

fn run(arguments: &[String]) -> Result<(), String> {
    let (workload, operation) = match arguments.len() {
        4 if arguments[1] == "--verify" => (&arguments[2], &arguments[3]),
        6 => (&arguments[1], &arguments[2]),
        _ => {
            return Err("usage: language_rust_pass <workload> <operation> <warmup_seconds> <repetitions> <min_seconds> \
                        | --verify <workload> <operation>; operation is reusedContains, reusedCount, reusedFind, or null"
                .into())
        }
    };
    let inputs = read_inputs(workload)?;
    match operation.as_str() {
        "reusedContains" => run_operation::<Contains>(arguments, &inputs),
        "reusedCount" => run_operation::<Count>(arguments, &inputs),
        "reusedFind" => run_operation::<Find>(arguments, &inputs),
        "null" => run_operation::<Null>(arguments, &inputs),
        _ => Err("unknown operation".into()),
    }
}

fn main() -> ExitCode {
    let arguments: Vec<String> = std::env::args().collect();
    match run(&arguments) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("{message}");
            ExitCode::FAILURE
        }
    }
}
