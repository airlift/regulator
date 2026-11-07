#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "re2/prog.h"
#include "re2/regexp.h"
#include "re2/re2.h"
#include "re2/stringpiece.h"

#include "random_corpus.h"

namespace {

enum class Mode {
  kCompileDump,
  kByteMap,
  kMatch,
  kOptionsMatch,
  kOptionsReplace,
  kOptionsFindAll,
  kRandomCorpus,
};

struct RegexpDeleter {
  void operator()(re2::Regexp* re) const {
    if (re) {
      re->Decref();
    }
  }
};

struct Options {
  Mode mode = Mode::kCompileDump;
  int flags = 0;
  std::string group;
};

void usage(const char* argv0) {
  std::cerr << "Usage: " << argv0 << " --mode=compile_dump|bytemap --flags=<int>\n"
            << "       " << argv0 << " --mode=match|options_match|options_replace|options_find_all\n"
            << "       " << argv0 << " --mode=random_corpus --group=<name>\n";
}

bool starts_with(const std::string& s, const char* prefix) {
  return s.rfind(prefix, 0) == 0;
}

std::string json_escape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (char c : s) {
    switch (c) {
      case '\\': out.append("\\\\"); break;
      case '"': out.append("\\\""); break;
      case '\n': out.append("\\n"); break;
      case '\r': out.append("\\r"); break;
      case '\t': out.append("\\t"); break;
      default: out.push_back(c); break;
    }
  }
  return out;
}

Options parse_args(int argc, char** argv) {
  Options options;
  bool have_mode = false;
  bool have_flags = false;

  for (int i = 1; i < argc; i++) {
    std::string arg(argv[i]);
    if (starts_with(arg, "--mode=")) {
      std::string mode = arg.substr(strlen("--mode="));
      if (mode == "compile_dump") {
        options.mode = Mode::kCompileDump;
      } else if (mode == "bytemap") {
        options.mode = Mode::kByteMap;
      } else if (mode == "match") {
        options.mode = Mode::kMatch;
      } else if (mode == "options_match") {
        options.mode = Mode::kOptionsMatch;
      } else if (mode == "options_replace") {
        options.mode = Mode::kOptionsReplace;
      } else if (mode == "options_find_all") {
        options.mode = Mode::kOptionsFindAll;
      } else if (mode == "random_corpus") {
        options.mode = Mode::kRandomCorpus;
      } else {
        std::cerr << "Unknown mode: " << mode << "\n";
        usage(argv[0]);
        std::exit(2);
      }
      have_mode = true;
    } else if (starts_with(arg, "--flags=")) {
      std::string value = arg.substr(strlen("--flags="));
      options.flags = std::stoi(value, nullptr, 0);
      have_flags = true;
    } else if (starts_with(arg, "--group=")) {
      options.group = arg.substr(strlen("--group="));
    } else if (arg == "--help" || arg == "-h") {
      usage(argv[0]);
      std::exit(0);
    } else {
      std::cerr << "Unknown arg: " << arg << "\n";
      usage(argv[0]);
      std::exit(2);
    }
  }

  if (!have_mode ||
      (options.mode != Mode::kMatch &&
       options.mode != Mode::kOptionsMatch &&
       options.mode != Mode::kOptionsReplace &&
       options.mode != Mode::kOptionsFindAll &&
       options.mode != Mode::kRandomCorpus && !have_flags) ||
      (options.mode == Mode::kRandomCorpus && options.group.empty())) {
    usage(argv[0]);
    std::exit(2);
  }

  return options;
}

int hex_value(char c) {
  if (c >= '0' && c <= '9') {
    return c - '0';
  }
  if (c >= 'a' && c <= 'f') {
    return c - 'a' + 10;
  }
  if (c >= 'A' && c <= 'F') {
    return c - 'A' + 10;
  }
  throw std::invalid_argument("invalid hexadecimal character");
}

std::string decode_hex(const std::string& value) {
  if (value == "-") {
    return std::string();
  }
  if ((value.size() & 1) != 0) {
    throw std::invalid_argument("hexadecimal value has odd length");
  }

  std::string decoded;
  decoded.reserve(value.size() / 2);
  for (size_t index = 0; index < value.size(); index += 2) {
    decoded.push_back(static_cast<char>((hex_value(value[index]) << 4) |
                                        hex_value(value[index + 1])));
  }
  return decoded;
}

std::vector<std::string> split_tabs(const std::string& line) {
  std::vector<std::string> fields;
  size_t start = 0;
  while (true) {
    size_t tab = line.find('\t', start);
    if (tab == std::string::npos) {
      fields.push_back(line.substr(start));
      return fields;
    }
    fields.push_back(line.substr(start, tab - start));
    start = tab + 1;
  }
}

re2::RE2::Anchor parse_anchor(const std::string& value) {
  if (value == "unanchored") {
    return re2::RE2::UNANCHORED;
  }
  if (value == "start") {
    return re2::RE2::ANCHOR_START;
  }
  if (value == "both") {
    return re2::RE2::ANCHOR_BOTH;
  }
  throw std::invalid_argument("unknown anchor: " + value);
}

re2::RE2::Options::Encoding parse_encoding(const std::string& value) {
  if (value == "utf8") {
    return re2::RE2::Options::EncodingUTF8;
  }
  if (value == "latin1") {
    return re2::RE2::Options::EncodingLatin1;
  }
  throw std::invalid_argument("unknown encoding: " + value);
}

bool parse_boolean(const std::string& value) {
  if (value == "true") {
    return true;
  }
  if (value == "false") {
    return false;
  }
  throw std::invalid_argument("expected true or false: " + value);
}

std::string group_offsets(absl::string_view text,
                          const std::vector<absl::string_view>& groups) {
  std::ostringstream out;
  for (size_t index = 0; index < groups.size(); index++) {
    if (index != 0) {
      out << ',';
    }
    const absl::string_view& group = groups[index];
    if (group.data() == nullptr) {
      out << "-1:-1";
      continue;
    }
    size_t start = static_cast<size_t>(group.data() - text.data());
    out << start << ':' << (start + group.size());
  }
  return out.str();
}

void emit_match(const std::vector<std::string>& fields) {
  if (fields.size() != 9) {
    throw std::invalid_argument(
        "match input requires 9 tab-separated fields: "
        "id, pattern hex, text hex, start, end, anchor, encoding, longest, groups");
  }

  const std::string& id = fields[0];
  std::string pattern = decode_hex(fields[1]);
  std::string text_value = decode_hex(fields[2]);
  size_t start = static_cast<size_t>(std::stoull(fields[3]));
  size_t end = static_cast<size_t>(std::stoull(fields[4]));
  re2::RE2::Anchor anchor = parse_anchor(fields[5]);
  re2::RE2::Options::Encoding encoding = parse_encoding(fields[6]);
  bool longest = parse_boolean(fields[7]);
  int group_count = std::stoi(fields[8]);

  if (start > end || end > text_value.size()) {
    throw std::invalid_argument("invalid match window");
  }
  if (group_count < 0) {
    throw std::invalid_argument("group count is negative");
  }

  re2::RE2::Options options;
  options.set_encoding(encoding);
  options.set_longest_match(longest);
  options.set_log_errors(false);
  re2::RE2 regexp(re2::StringPiece(pattern), options);

  std::cout << "{\"id\":\"" << json_escape(id)
            << "\",\"patternHex\":\"" << fields[1]
            << "\",\"textHex\":\"" << fields[2]
            << "\",\"start\":\"" << fields[3]
            << "\",\"end\":\"" << fields[4]
            << "\",\"anchor\":\"" << fields[5]
            << "\",\"encoding\":\"" << fields[6]
            << "\",\"longest\":" << (longest ? "true" : "false")
            << ",\"groupCount\":\"" << fields[8] << "\"";

  if (!regexp.ok()) {
    std::cout << ",\"ok\":false,\"matched\":false,\"groups\":\"\""
              << ",\"error\":\"" << json_escape(regexp.error()) << "\"}\n";
    return;
  }

  absl::string_view text(text_value);
  std::vector<absl::string_view> groups(static_cast<size_t>(group_count));
  bool matched = regexp.Match(text, start, end, anchor, groups.data(), group_count);

  std::cout << ",\"ok\":true,\"matched\":" << (matched ? "true" : "false")
            << ",\"groups\":\"" << (matched ? group_offsets(text, groups) : "")
            << "\",\"error\":null}\n";
}

// The option booleans that follow the encoding in a row.
constexpr size_t kOptionBooleans = 10;

// Applies the encoding at fields[first] and the option booleans that follow it.
re2::RE2::Options parse_options(const std::vector<std::string>& fields, size_t first) {
  re2::RE2::Options options;
  options.set_encoding(parse_encoding(fields[first]));
  options.set_posix_syntax(parse_boolean(fields[first + 1]));
  options.set_longest_match(parse_boolean(fields[first + 2]));
  options.set_literal(parse_boolean(fields[first + 3]));
  options.set_never_nl(parse_boolean(fields[first + 4]));
  options.set_dot_nl(parse_boolean(fields[first + 5]));
  options.set_never_capture(parse_boolean(fields[first + 6]));
  options.set_case_sensitive(parse_boolean(fields[first + 7]));
  options.set_perl_classes(parse_boolean(fields[first + 8]));
  options.set_word_boundary(parse_boolean(fields[first + 9]));
  options.set_one_line(parse_boolean(fields[first + 10]));
  options.set_log_errors(false);
  return options;
}

// The last compiled pattern, reused while consecutive rows ask for the same pattern and options.
const re2::RE2& cached_regexp(const std::string& pattern, const std::vector<std::string>& fields, size_t first) {
  static std::string cached_key;
  static std::unique_ptr<re2::RE2> cached;
  std::string key = pattern;
  for (size_t index = first; index <= first + kOptionBooleans; index++) {
    key.push_back('\t');
    key.append(fields[index]);
  }
  if (!cached || key != cached_key) {
    cached = std::make_unique<re2::RE2>(re2::StringPiece(pattern), parse_options(fields, first));
    cached_key = key;
  }
  return *cached;
}

std::string encode_hex(absl::string_view value) {
  static const char kDigits[] = "0123456789abcdef";
  std::string encoded;
  encoded.reserve(value.size() * 2);
  for (char c : value) {
    unsigned char byte = static_cast<unsigned char>(c);
    encoded.push_back(kDigits[byte >> 4]);
    encoded.push_back(kDigits[byte & 0xF]);
  }
  return encoded;
}

// Replace, GlobalReplace, and Extract of one rewrite, with the pattern's capture metadata: its
// capturing groups, without group zero, and their names, and why the rewrite is invalid.
void emit_options_replace(const std::vector<std::string>& fields) {
  if (fields.size() != 15) {
    throw std::invalid_argument(
        "options replace input requires 15 tab-separated fields: "
        "id, pattern hex, text hex, encoding, posix, longest, literal, never newline, "
        "dot newline, never capture, case sensitive, Perl classes, word boundary, one line, "
        "rewrite hex");
  }

  const std::string& id = fields[0];
  std::string pattern = decode_hex(fields[1]);
  std::string text_value = decode_hex(fields[2]);
  std::string rewrite = decode_hex(fields[14]);
  const re2::RE2& regexp = cached_regexp(pattern, fields, 3);

  std::cout << "{\"id\":\"" << json_escape(id) << "\"";
  if (!regexp.ok()) {
    std::cout << ",\"ok\":false,\"error\":\"" << json_escape(regexp.error()) << "\"}\n";
    return;
  }

  std::ostringstream names;
  for (const auto& entry : regexp.NamedCapturingGroups()) {
    if (names.tellp() > 0) {
      names << ',';
    }
    names << entry.first << '=' << entry.second;
  }
  std::string rewrite_error;
  bool rewrite_ok = regexp.CheckRewriteString(rewrite, &rewrite_error);

  std::string replaced = text_value;
  bool replace_matched = re2::RE2::Replace(&replaced, regexp, rewrite);
  std::string global = text_value;
  int global_count = re2::RE2::GlobalReplace(&global, regexp, rewrite);
  std::string extracted;
  bool extract_matched = re2::RE2::Extract(text_value, regexp, rewrite, &extracted);

  std::cout << ",\"ok\":true,\"error\":null"
            << ",\"groupCount\":\"" << regexp.NumberOfCapturingGroups() << "\""
            << ",\"groupNames\":\"" << json_escape(names.str()) << "\""
            << ",\"rewriteOk\":" << (rewrite_ok ? "true" : "false")
            << ",\"rewriteError\":\"" << json_escape(rewrite_error) << "\""
            << ",\"replaceMatched\":" << (replace_matched ? "true" : "false")
            << ",\"replaceHex\":\"" << (replace_matched ? encode_hex(replaced) : "") << "\""
            << ",\"globalCount\":\"" << global_count << "\""
            << ",\"globalHex\":\"" << (global_count > 0 ? encode_hex(global) : "") << "\""
            << ",\"extractMatched\":" << (extract_matched ? "true" : "false")
            << ",\"extractHex\":\"" << (extract_matched ? encode_hex(extracted) : "") << "\""
            << "}\n";
}

// The first match in the window under the anchor, with the requested groups, group zero
// included.
void emit_options_match(const std::vector<std::string>& fields) {
  if (fields.size() != 18) {
    throw std::invalid_argument(
        "options match input requires 18 tab-separated fields: "
        "id, pattern hex, text hex, start, end, anchor, encoding, posix, longest, "
        "literal, never newline, dot newline, never capture, case sensitive, "
        "Perl classes, word boundary, one line, groups");
  }

  const std::string& id = fields[0];
  std::string pattern = decode_hex(fields[1]);
  std::string text_value = decode_hex(fields[2]);
  size_t start = static_cast<size_t>(std::stoull(fields[3]));
  size_t end = static_cast<size_t>(std::stoull(fields[4]));
  re2::RE2::Anchor anchor = parse_anchor(fields[5]);
  int group_count = std::stoi(fields[17]);
  if (start > end || end > text_value.size()) {
    throw std::invalid_argument("invalid options match window");
  }
  if (group_count < 0) {
    throw std::invalid_argument("group count is negative");
  }

  const re2::RE2& regexp = cached_regexp(pattern, fields, 6);

  std::cout << "{\"id\":\"" << json_escape(id)
            << "\",\"patternHex\":\"" << fields[1]
            << "\",\"textHex\":\"" << fields[2]
            << "\",\"start\":\"" << fields[3]
            << "\",\"end\":\"" << fields[4]
            << "\",\"anchor\":\"" << fields[5]
            << "\",\"encoding\":\"" << fields[6]
            << "\",\"posix\":" << fields[7]
            << ",\"longest\":" << fields[8]
            << ",\"literal\":" << fields[9]
            << ",\"neverNewline\":" << fields[10]
            << ",\"dotMatchesNewline\":" << fields[11]
            << ",\"neverCapture\":" << fields[12]
            << ",\"caseSensitive\":" << fields[13]
            << ",\"perlClasses\":" << fields[14]
            << ",\"wordBoundary\":" << fields[15]
            << ",\"oneLine\":" << fields[16]
            << ",\"groupCount\":\"" << fields[17] << "\"";

  if (!regexp.ok()) {
    std::cout << ",\"ok\":false,\"matched\":false,\"groups\":\"\""
              << ",\"error\":\"" << json_escape(regexp.error()) << "\"}\n";
    return;
  }

  absl::string_view text(text_value);
  std::vector<absl::string_view> groups(static_cast<size_t>(group_count));
  bool matched = regexp.Match(text, start, end, anchor, groups.data(), group_count);
  std::cout << ",\"ok\":true,\"matched\":" << (matched ? "true" : "false")
            << ",\"groups\":\"" << (matched ? group_offsets(text, groups) : "")
            << "\",\"error\":null}\n";
}

// The offset after an empty match at `position`, where Regulator's matcher searches next: one
// byte later in Latin-1, and in UTF-8 past the character there, measured as Slice's
// lengthOfCodePointSafe does, with a malformed sequence ending at the first byte that does not
// continue it or at the end of the text.
size_t after_empty_match(absl::string_view text, size_t position, bool latin1) {
  unsigned char lead = static_cast<unsigned char>(text[position]);
  size_t length;
  if (latin1 || lead < 0xC0 || lead >= 0xFE) {
    length = 1;
  } else if (lead < 0xE0) {
    length = 2;
  } else if (lead < 0xF0) {
    length = 3;
  } else if (lead < 0xF8) {
    length = 4;
  } else if (lead < 0xFC) {
    length = 5;
  } else {
    length = 6;
  }
  size_t advance = 1;
  while (advance < length && position + advance < text.size() && (static_cast<unsigned char>(text[position + advance]) & 0xC0) == 0x80) {
    advance++;
  }
  return position + advance;
}

// The successive matches of the pattern in the window [start, end) of the text, each with the
// requested groups, group zero included. The window is all the context the search sees, and
// matches are found as Regulator's RegexpMatcher finds them: the search
// after a nonempty match starts at its end, an empty match at the end of the window is the
// last, and the search after any other empty match starts past the character after it.
void emit_options_find_all(const std::vector<std::string>& fields) {
  if (fields.size() != 18) {
    throw std::invalid_argument(
        "options find all input requires 18 tab-separated fields: "
        "id, pattern hex, text hex, start, end, encoding, posix, longest, literal, "
        "never newline, dot newline, never capture, case sensitive, Perl classes, "
        "word boundary, one line, groups, limit");
  }

  const std::string& id = fields[0];
  std::string pattern = decode_hex(fields[1]);
  std::string text_value = decode_hex(fields[2]);
  size_t start = static_cast<size_t>(std::stoull(fields[3]));
  size_t end = static_cast<size_t>(std::stoull(fields[4]));
  int group_count = std::stoi(fields[16]);
  int limit = std::stoi(fields[17]);
  if (start > end || end > text_value.size()) {
    throw std::invalid_argument("invalid options find all window");
  }
  if (group_count < 1) {
    throw std::invalid_argument("group count must include group zero");
  }
  if (limit < 0) {
    throw std::invalid_argument("limit is negative");
  }

  const re2::RE2& regexp = cached_regexp(pattern, fields, 5);

  std::cout << "{\"id\":\"" << json_escape(id) << "\"";
  if (!regexp.ok()) {
    std::cout << ",\"ok\":false,\"error\":\"" << json_escape(regexp.error()) << "\"}\n";
    return;
  }

  absl::string_view text(text_value);
  absl::string_view window = text.substr(start, end - start);
  bool latin1 = parse_encoding(fields[5]) == re2::RE2::Options::EncodingLatin1;
  std::vector<absl::string_view> groups(static_cast<size_t>(group_count));
  std::ostringstream matches;
  int found = 0;
  bool more = false;
  size_t position = 0;
  while (regexp.Match(window, position, window.size(), re2::RE2::UNANCHORED, groups.data(), group_count)) {
    if (found == limit) {
      more = true;
      break;
    }
    if (found != 0) {
      matches << ';';
    }
    matches << group_offsets(text, groups);
    found++;
    size_t match_start = static_cast<size_t>(groups[0].data() - window.data());
    size_t match_end = match_start + groups[0].size();
    if (match_end != match_start) {
      position = match_end;
    } else if (match_end == window.size()) {
      break;
    } else {
      position = after_empty_match(window, match_end, latin1);
    }
  }
  std::cout << ",\"ok\":true,\"error\":null,\"matches\":\"" << matches.str() << "\",\"more\":" << (more ? "true" : "false") << "}\n";
}

std::string flags_string(int flags) {
  std::ostringstream out;
  out << "0x" << std::hex << std::nouppercase << flags;
  return out.str();
}

void emit_error(const std::string& pattern, const std::string& flags, const std::string& error) {
  std::cout << "{\"pattern\":\"" << json_escape(pattern)
            << "\",\"flags\":\"" << flags << "\",\"ok\":false,\"error\":\"" << json_escape(error) << "\"}\n";
}

void emit_dump(const std::string& pattern, const std::string& flags, const std::string& dump) {
  std::cout << "{\"pattern\":\"" << json_escape(pattern)
            << "\",\"flags\":\"" << flags << "\",\"ok\":true,\"dump\":\"" << json_escape(dump) << "\"}\n";
}

void emit_bytemap(const std::string& pattern, const std::string& flags, const std::string& bytemap) {
  std::cout << "{\"pattern\":\"" << json_escape(pattern)
            << "\",\"flags\":\"" << flags << "\",\"ok\":true,\"bytemap\":\"" << json_escape(bytemap) << "\"}\n";
}

}  // namespace

int main(int argc, char** argv) {
  Options options = parse_args(argc, argv);
  if (options.mode == Mode::kRandomCorpus) {
    generate_random_corpus(options.group, std::cout);
    return 0;
  }
  std::string flags = flags_string(options.flags);

  std::string line;
  while (std::getline(std::cin, line)) {
    if (!line.empty() && line.back() == '\r') {
      line.pop_back();
    }
    if (!line.empty() && line[0] == '#') {
      continue;
    }

    if (options.mode == Mode::kMatch) {
      try {
        emit_match(split_tabs(line));
      } catch (const std::exception& error) {
        std::cerr << "Invalid match input: " << error.what() << " -> " << line << "\n";
        return 2;
      }
      continue;
    }
    if (options.mode == Mode::kOptionsMatch) {
      try {
        emit_options_match(split_tabs(line));
      } catch (const std::exception& error) {
        std::cerr << "Invalid options match input: " << error.what() << " -> " << line << "\n";
        return 2;
      }
      continue;
    }
    if (options.mode == Mode::kOptionsReplace) {
      try {
        emit_options_replace(split_tabs(line));
      } catch (const std::exception& error) {
        std::cerr << "Invalid options replace input: " << error.what() << " -> " << line << "\n";
        return 2;
      }
      continue;
    }
    if (options.mode == Mode::kOptionsFindAll) {
      try {
        emit_options_find_all(split_tabs(line));
      } catch (const std::exception& error) {
        std::cerr << "Invalid options find all input: " << error.what() << " -> " << line << "\n";
        return 2;
      }
      continue;
    }

    re2::RegexpStatus status;
    std::unique_ptr<re2::Regexp, RegexpDeleter> re(
        re2::Regexp::Parse(re2::StringPiece(line),
                          static_cast<re2::Regexp::ParseFlags>(options.flags),
                          &status));
    if (!status.ok()) {
      emit_error(line, flags, status.Text());
      continue;
    }

    std::unique_ptr<re2::Prog> prog(re->CompileToProg(0));
    if (!prog) {
      emit_error(line, flags, "compile failed");
      continue;
    }

    switch (options.mode) {
      case Mode::kCompileDump:
        emit_dump(line, flags, prog->Dump());
        break;
      case Mode::kByteMap:
        emit_bytemap(line, flags, prog->DumpByteMap());
        break;
      case Mode::kMatch:
      case Mode::kOptionsMatch:
      case Mode::kOptionsReplace:
      case Mode::kOptionsFindAll:
      case Mode::kRandomCorpus:
        std::abort();
    }
  }

  return 0;
}
