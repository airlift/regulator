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

//! The pinned Rust `regex` crate behind a C interface for the native language runners.
//!
//! Every call that borrows its input as `&str` takes text the runner already
//! validated as UTF-8 before timing, so the shim does not check it again. Each
//! search runs inside one call per input, with the crate's own iteration except
//! for capture counting, whose shim-managed loop reuses one set of locations.

use std::ffi::{c_char, c_void, CString};
use std::ptr;

use regex::{CaptureLocations, Regex, RegexBuilder};

/// Receives one match: `groups` start and end offsets, `usize::MAX` for a group that did not participate.
pub type MatchCallback = unsafe extern "C" fn(context: *mut c_void, spans: *const usize, groups: usize);

unsafe fn bytes<'a>(data: *const u8, length: usize) -> &'a [u8] {
    if length == 0 { &[] } else { std::slice::from_raw_parts(data, length) }
}

unsafe fn text<'a>(data: *const u8, length: usize) -> &'a str {
    std::str::from_utf8_unchecked(bytes(data, length))
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_valid_utf8(data: *const u8, length: usize) -> bool {
    std::str::from_utf8(bytes(data, length)).is_ok()
}

/// Compiles with the builder settings of tools/rust-regex-golden. On failure returns null
/// and stores a message for `language_rust_regex_free_error`.
#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_compile(
    pattern: *const u8,
    length: usize,
    unicode: bool,
    case_insensitive: bool,
    error: *mut *mut c_char,
) -> *mut Regex {
    match RegexBuilder::new(text(pattern, length)).unicode(unicode).case_insensitive(case_insensitive).build() {
        Ok(regex) => Box::into_raw(Box::new(regex)),
        Err(failure) => {
            *error = CString::new(failure.to_string().replace('\0', "\\0")).unwrap().into_raw();
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_free(regex: *mut Regex) {
    drop(Box::from_raw(regex));
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_free_error(error: *mut c_char) {
    drop(CString::from_raw(error));
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_capture_locations(regex: *const Regex) -> *mut CaptureLocations {
    Box::into_raw(Box::new((*regex).capture_locations()))
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_free_capture_locations(locations: *mut CaptureLocations) {
    drop(Box::from_raw(locations));
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_is_match(regex: *const Regex, data: *const u8, length: usize) -> bool {
    (*regex).is_match(text(data, length))
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_count(regex: *const Regex, data: *const u8, length: usize) -> u64 {
    (*regex).find_iter(text(data, length)).count() as u64
}

#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_count_spans(regex: *const Regex, data: *const u8, length: usize) -> u64 {
    (*regex).find_iter(text(data, length)).map(|found| found.len() as u64).sum()
}

/// Visits each match's groups with reused locations. `captures_iter` would clone
/// the groups for every match, so this follows its iteration rule directly: search
/// from the previous match end, and when an empty match lands on that end, search
/// once more from one byte later.
fn each_capture(regex: &Regex, locations: &mut CaptureLocations, haystack: &str, mut visit: impl FnMut(&CaptureLocations)) {
    let mut start = 0;
    let mut last_end = None;
    while let Some(mut found) = regex.captures_read_at(locations, haystack, start) {
        if found.is_empty() && Some(found.end()) == last_end {
            match regex.captures_read_at(locations, haystack, start + 1) {
                Some(next) => found = next,
                None => break,
            }
        }
        visit(locations);
        start = found.end();
        last_end = Some(found.end());
    }
}

/// Counts the participating groups of every match, including the whole match.
#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_count_captures(
    regex: *const Regex,
    locations: *mut CaptureLocations,
    data: *const u8,
    length: usize,
) -> u64 {
    let mut result = 0;
    each_capture(&*regex, &mut *locations, text(data, length), |groups| {
        result += (0..groups.len()).filter(|&index| groups.get(index).is_some()).count() as u64;
    });
    result
}

/// Reports each match from the crate's own iterator, all groups when `captures`
/// is set and otherwise the whole match. Returns false when the timed iteration
/// disagrees: with `captures`, the shim-managed loop must find the same groups;
/// otherwise counting must find the same number of matches.
#[no_mangle]
pub unsafe extern "C" fn language_rust_regex_trace(
    regex: *const Regex,
    data: *const u8,
    length: usize,
    captures: bool,
    callback: MatchCallback,
    context: *mut c_void,
) -> bool {
    let regex = &*regex;
    let haystack = text(data, length);
    let mut matches: Vec<Vec<usize>> = Vec::new();
    if captures {
        for found in regex.captures_iter(haystack) {
            matches.push(found.iter().flat_map(|group| group.map_or([usize::MAX; 2], |span| [span.start(), span.end()])).collect());
        }
        let mut timed: Vec<Vec<usize>> = Vec::new();
        each_capture(regex, &mut regex.capture_locations(), haystack, |groups| {
            timed.push((0..groups.len()).flat_map(|index| groups.get(index).map_or([usize::MAX; 2], |(start, end)| [start, end])).collect());
        });
        if timed != matches {
            return false;
        }
    } else {
        matches.extend(regex.find_iter(haystack).map(|found| vec![found.start(), found.end()]));
        // Counting takes the crate's faster path, which finds only where each match ends.
        if regex.find_iter(haystack).count() != matches.len() {
            return false;
        }
    }
    for spans in &matches {
        callback(context, spans.as_ptr(), spans.len() / 2);
    }
    true
}
