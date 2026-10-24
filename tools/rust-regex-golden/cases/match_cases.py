# Readable source for ../match-cases.tsv. Run emit.py after editing.
#
# Each case is (id, pattern, options, text, start, end). Patterns and texts are
# Python strings, encoded as UTF-8 hex by emit.py, except that a bytes-API text
# may be a bytes object holding any bytes. Options use the encoding in
# ../README.md. A window is a pair of byte offsets into the encoded text; "-"
# means the whole text. A bytes-API window may split a character.
cases = []


def section(title):
    cases.append(("#", title))


def case(id, pattern, text, options="-"):
    cases.append((id, pattern, options, text, "-", "-"))


def window(id, pattern, text, start, end, options="-"):
    cases.append((id, pattern, options, text, str(start), str(end)))


section("Basic matching and capture groups")
case("basic_literal", "abc", "xabcx")
case("basic_no_match", "abc", "xyz")
case("basic_empty_text", "a", "")
case("basic_empty_pattern_empty_text", "", "")
case("basic_named_groups", "(?P<year>\\d{4})-(?<month>\\d{2})-(\\d{2})", "on 2024-01-15 and 2025-12-31")
case("basic_unmatched_optional_group", "(a)|(b)", "b")
case("basic_nested_groups", "((a)(b(c)?))+", "abab")
case("basic_zero_repetition_keeps_slot", "(a){0}(a)", "aa")
case("basic_zero_repetition_named", "(?P<x>a){0}b", "b")
case("basic_never_matching_named", "(?<x>a)[a&&b]|b", "b")
case("basic_dot_excludes_newline", ".", "a\nb")
case("basic_dot_matches_newline_flag", "(?s).", "\n")
case("basic_dot_matches_newline_option", ".", "\n", "s")
case("basic_dot_supplementary", ".", "\U0001F600x")
case("basic_dot_count_multibyte", "...", "\u00e9\u2603\U0001F600")
case("basic_many_matches_capped", "a", "a" * 100)
case("basic_many_empty_matches_capped", "", "x" * 99)
case("basic_class_unicode_range", "[\u00e0-\u00ff]+", "abc\u00e9\u00e8\u00ffz")
case("basic_negated_class_newline", "[^a]+", "b\nc")
case("basic_class_difference", "[\\pL--[a-z]]+", "abcD\u00c9F\u00e9")
case("basic_counted", "a{2,3}", "aaaaaaa")
case("basic_lazy_counted", "a{2,3}?", "aaaaaaa")
case("basic_property_greek", "\\p{Greek}+", "abc\u03b1\u03b2\u03b3def")
case("basic_property_han", "\\p{Han}+", "x\u4e2d\u6587y")
case("basic_property_nd", "\\p{Nd}+", "a0\u0663\u0967z")
case("basic_property_emoji", "\\p{Emoji_Presentation}", "a\U0001F600b")
case("basic_escaped_dot", "a\\.b", "axb a.b")
case("basic_alternation_of_literals", "foo|bar|baz", "xbazfoobar")
case("basic_case_insensitive_option", "abc", "xABCx", "i")
case("basic_verbose_option", "a b # comment", "ab", "x")
case("basic_inline_flag_scope", "a(?i:b)c", "aBc aBC")

section("CRLF mode: dot")
for name, text in [("carriage_return", "\r"), ("newline", "\n"), ("letter", "a"), ("crlf", "\r\n")]:
    case(f"crlf_dot_{name}", "(?R).", text)
    case(f"crlf_dot_default_{name}", ".", text)
    case(f"crlf_dot_all_{name}", "(?Rs).", text)
case("crlf_dot_option", ".", "\r\n", "R")
case("crlf_dot_plus", "(?R).+", "ab\rcd\nef\r\ngh")
case("crlf_dot_plus_default", ".+", "ab\rcd\nef\r\ngh")
case("crlf_dot_ascii", "(?R-u).", "\r\na")
case("crlf_negated_class_unaffected", "(?R)[^a]", "\r\n")
case("crlf_non_newline_class_unaffected", "(?R)\\S", "\r\n")

section("CRLF mode: anchors")
case("crlf_multi_line_dollar", "(?mR)$", "a\r\nb\r\n")
case("crlf_multi_line_caret", "(?mR)^", "a\r\nb\r\n")
case("crlf_multi_line_line", "(?mR)^.*$", "a\r\nb\rc\nd")
case("crlf_multi_line_empty_lines", "(?mR)^$", "\r\n\r\n")
case("crlf_multi_line_lone_carriage_return", "(?mR)^$", "\r\r")
case("crlf_multi_line_newline_carriage_return", "(?mR)^$", "\n\r")
case("crlf_multi_line_between_cr_and_lf", "(?mR)a$", "a\r\n")
case("crlf_multi_line_dollar_before_cr", "(?mR)a$", "a\rb")
case("crlf_multi_line_default_dollar_before_cr", "(?m)a$", "a\r\nb")
case("crlf_multi_line_default_line", "(?m)^.*$", "a\r\nb")
case("crlf_without_multi_line_dollar", "(?R)a$", "a\r\n")
case("crlf_without_multi_line_caret", "(?R)^b", "a\r\nb")
case("crlf_multi_line_caret_after_cr", "(?mR)^b", "a\rb")
window("crlf_multi_line_window_between_cr_and_lf", "(?mR)^", "a\r\nb", 2, 4)
window("crlf_multi_line_window_after_crlf", "(?mR)^b", "a\r\nb", 3, 4)

section("Dollar and end-of-text")
case("dollar_trailing_newline", "a$", "a\n")
case("dollar_trailing_newline_end_text", "a\\z", "a\n")
case("dollar_end_of_text", "a$", "ba")
case("dollar_multi_line_trailing_newline", "(?m)a$", "a\n")
case("dollar_multi_line_lines", "(?m)^\\w+$", "one\ntwo\n\nthree\n")
case("dollar_empty_iteration", "$", "a\n")
case("dollar_multi_line_empty_iteration", "(?m)$", "a\nb\n")
case("caret_multi_line_empty_iteration", "(?m)^", "a\nb\n")
case("caret_multi_line_after_trailing_newline", "(?m)^$", "a\n")
case("begin_text_multi_line", "(?m)\\Aa", "b\na")
case("end_text_multi_line", "(?m)a\\z", "a\nb")
case("dollar_carriage_return_default", "(?m)a$", "a\r")
case("dollar_unicode_line_separator", "(?m)a$", "a\u2028")
case("dollar_next_line", "(?m)a$", "a\u0085")
case("dollar_dot_all_trailing_newline", "(?s)a.$", "a\n")

section("Perl classes: Unicode and ASCII")
perl_text = "a_Z9\u00e9\u00df\u0663\u0967\u00a0\u2003\u0085\u3000\u200d\u0301\u2163\u00bd\u00b2\t\x0b\x0c\r\n -"
for name, cls in [("word", "\\w"), ("non_word", "\\W"), ("digit", "\\d"), ("non_digit", "\\D"),
                  ("space", "\\s"), ("non_space", "\\S")]:
    case(f"perl_{name}", cls, perl_text)
    case(f"perl_{name}_ascii", f"(?-u:{cls})", perl_text)
case("perl_word_run", "\\w+", "caf\u00e9 na\u00efve \u0663\u0663 x\u200dy e\u0301")
case("perl_word_run_ascii", "(?-u:\\w+)", "caf\u00e9 na\u00efve \u0663\u0663 x\u200dy e\u0301")
case("perl_word_run_option_ascii", "\\w+", "caf\u00e9 abc", "-u")
case("perl_digit_run", "\\d+", "12\u0663\u0664 \u00bd \u00b2 \u2163 \uff11")
case("perl_digit_run_ascii", "(?-u:\\d+)", "12\u0663\u0664 \u00bd \u00b2 \u2163 \uff11")
case("perl_space_run", "\\s+", "a\u00a0\u2003\u3000\u0085\u2028\u2029\u202f\u205f\u1680b")
case("perl_space_run_ascii", "(?-u:\\s+)", "a \t\x0b\x0c\r\n\u00a0b")
case("perl_space_not_zero_width", "\\s", "\u200b\ufeff\u180e")
case("perl_word_class_in_brackets", "[\\w&&\\p{Greek}]+", "abc\u03b1\u03b2")
case("perl_class_group_syntax_is_literal", "[^(?-u:\\w)]+", "ab\u00e9\u00e8c")
case("perl_case_insensitive_word", "(?i)\\w", "\u212a")
case("posix_alpha_ascii_only", "[[:alpha:]]+", "ab\u00e9c")
case("posix_word_ascii_only", "[[:word:]]+", "a_1\u00e9")
case("posix_space", "[[:space:]]+", "a\x0b\x0c \u00a0b")

section("Word boundaries")
wb_text = "caf\u00e9 x_1 -a- \u0663\u0663 \u03b1\u03b2"
for name, pattern in [("word_boundary", "\\b"), ("not_word_boundary", "\\B"),
                      ("word_start", "\\b{start}"), ("word_end", "\\b{end}"),
                      ("word_start_half", "\\b{start-half}"), ("word_end_half", "\\b{end-half}"),
                      ("word_start_angle", "\\<"), ("word_end_angle", "\\>")]:
    case(f"boundary_{name}", pattern, wb_text)
    case(f"boundary_{name}_ascii", f"(?-u:{pattern})", wb_text)
    case(f"boundary_{name}_empty_text", pattern, "")
case("boundary_word_unicode_letter_edge", "\\b\u00e9", "a\u00e9 \u00e9")
case("boundary_word_ascii_letter_edge", "(?-u:\\b)x", "\u00e9x")
case("boundary_word_combining_mark", "\\b", "e\u0301")
case("boundary_word_zero_width_joiner", "\\b", "a\u200db")
case("boundary_word_words", "\\b\\w+\\b", "one t\u00e9st \u03b1\u03b2\u03b3")
case("boundary_start_end_words", "\\b{start}\\w+\\b{end}", "one t\u00e9st \u03b1\u03b2\u03b3")
case("boundary_half_hyphen", "\\b{start-half}-\\b{end-half}", "- a-b -")
case("boundary_angle_words", "\\<\\w+\\>", "(ab) cd")
case("boundary_not_word_multibyte", "\\B", "\u00e9\u00e9")
case("boundary_not_word_ascii_multibyte", "(?-u:\\B)", "\u00e9\u00e9")

section("Case folding")
for name, pattern, text in [
    ("kelvin_lower", "(?i)k", "kK\u212a"),
    ("kelvin_upper", "(?i)K", "kK\u212a"),
    ("kelvin_sign", "(?i)\u212a", "kK\u212a"),
    ("kelvin_class", "(?i)[k]", "kK\u212a"),
    ("kelvin_range", "(?i)[a-z]", "\u212a\u017f"),
    ("kelvin_ascii", "(?i-u)k", "kK\u212a"),
    ("kelvin_ascii_range", "(?i-u:[a-z])", "kK\u212a\u017f"),
    ("long_s_lower", "(?i)s", "sS\u017f"),
    ("long_s_upper", "(?i)S", "sS\u017f"),
    ("long_s", "(?i)\u017f", "sS\u017f"),
    ("sharp_s", "(?i)\u00df", "\u00df\u1e9e ss SS"),
    ("capital_sharp_s", "(?i)\u1e9e", "\u00df\u1e9e ss SS"),
    ("double_s", "(?i)ss", "\u00df\u1e9e ss SS sS"),
    ("sigma_capital", "(?i)\u03a3", "\u03a3\u03c3\u03c2"),
    ("sigma_small", "(?i)\u03c3", "\u03a3\u03c3\u03c2"),
    ("sigma_final", "(?i)\u03c2", "\u03a3\u03c3\u03c2"),
    ("sigma_class", "(?i)[\u03c3]", "\u03a3\u03c3\u03c2"),
    ("sigma_not_case_insensitive", "\u03c3", "\u03a3\u03c3\u03c2"),
    ("titlecase_dz", "(?i)\u01c5", "\u01c4\u01c5\u01c6"),
    ("titlecase_dz_upper", "(?i)\u01c4", "\u01c4\u01c5\u01c6"),
    ("dotted_capital_i", "(?i)\u0130", "i I \u0130 \u0131"),
    ("dotless_small_i", "(?i)\u0131", "i I \u0130 \u0131"),
    ("ascii_i", "(?i)i", "i I \u0130 \u0131"),
    ("greek_iota_dialytika_tonos", "(?i)\u0390", "\u0390\u1fd3"),
    ("greek_iota_dialytika_oxia", "(?i)\u1fd3", "\u0390\u1fd3"),
    ("micro_sign", "(?i)\u00b5", "\u00b5\u03bc\u039c"),
    ("greek_mu", "(?i)\u03bc", "\u00b5\u03bc\u039c"),
    ("angstrom_sign", "(?i)\u212b", "\u00e5\u00c5\u212b"),
    ("a_ring", "(?i)\u00e5", "\u00e5\u00c5\u212b"),
    ("ohm_sign", "(?i)\u2126", "\u03c9\u03a9\u2126"),
    ("theta_symbol", "(?i)\u03d1", "\u03b8\u0398\u03d1\u03f4"),
    ("cherokee", "(?i)\u13a0", "\u13a0\uab70"),
    ("deseret", "(?i)\U00010400", "\U00010400\U00010428"),
    ("latin_ligature_ff", "(?i)\ufb00", "\ufb00 ff FF"),
    ("range_greek", "(?i)[\u03b1-\u03c9]+", "\u0391\u0392\u03a3\u03c2\u00b5"),
    ("negated_class", "(?i)[^k]", "kK\u212ax"),
    ("negated_class_ascii_range", "(?i)[^a-z]", "aZ\u212a\u017f1"),
    ("property_lowercase", "(?i)\\p{Ll}", "aA\u00c9"),
    ("property_uppercase_negated", "(?i)\\P{Lu}", "aA1"),
    ("perl_word_negated", "(?i)\\W", "\u212a-"),
    ("literal_string", "(?i)stra\u00dfe", "STRASSE Stra\u1e9eE STRA\u00dfE"),
    ("disabled_scope", "(?i)a(?-i)b", "AB Ab"),
]:
    case(f"fold_{name}", pattern, text)
case("fold_option_flag", "k", "\u212a", "i")

section("Unicode disabled literals")
case("ascii_literal_non_ascii", "(?-u:\u00e9)", "e\u00e9")
case("ascii_braced_hex_is_character", "(?-u:\\x{E9})", "e\u00e9")
case("ascii_case_insensitive_non_ascii", "(?i-u:\u00e9)", "\u00e9\u00c9")
case("ascii_case_insensitive_ascii", "(?i-u:e)", "eE\u00e9")

section("Stacked repetition")
case("repeat_stacked_star", "a**", "aaa")
case("repeat_possessive_looking_is_stacked", "a*+a", "aaa")
case("repeat_stacked_lazy_then_star", "(a*?)*", "aa")
case("repeat_spaces_in_counted_braces", "a{ 2 }", "aaa")

section("Swap greed")
case("swap_greed_star", "(?U)a*", "aaa")
case("swap_greed_star_lazy", "(?U)a*?", "aaa")
case("swap_greed_plus", "(?U)a+", "aaa")
case("swap_greed_plus_lazy", "(?U)a+?", "aaa")
case("swap_greed_question", "(?U)a?", "a")
case("swap_greed_counted", "(?U)a{1,3}", "aaa")
case("swap_greed_counted_lazy", "(?U)a{1,3}?", "aaa")
case("swap_greed_option", "a+", "aaa", "U")
case("swap_greed_scoped", "(?U:a+)a+", "aaaa")
case("swap_greed_cleared", "(?U)a+(?-U)b+", "aabbb")
case("swap_greed_captures", "(?U)(a+)(a*)b", "aaab")

section("Empty-match iteration over multibyte text")
mb = "\u00e9\u2603\U0001F600"
case("empty_iter_pattern_empty", "", mb)
case("empty_iter_star_absent", "x*", mb)
case("empty_iter_star_mixed", "a*", "a\u00e9a\u2603")
case("empty_iter_lazy_star", "\u00e9*?", "\u00e9\u00e9")
case("empty_iter_word_boundary", "\\b", "\u00e9 \u00e9")
case("empty_iter_ascii_word_boundary", "(?-u:\\b)", "a\u00e9b")
case("empty_iter_not_ascii_word_boundary", "(?-u:\\B)", mb)
case("empty_iter_optional_group", "(\u00e9)?", "x\u00e9x")
case("empty_iter_after_nonempty", "\u2603|", "a\u2603b")
case("empty_iter_alternation_empty_first", "|\u2603", "a\u2603b")
case("empty_iter_word_start", "\\b{start}", "\u00e9a b")
case("empty_iter_ascii_word_start_half", "(?-u:\\b{start-half})", "\u00e9a")
case("empty_iter_ascii_word_end_half", "(?-u:\\b{end-half})", "a\u00e9")
case("empty_iter_case_insensitive", "(?i)\u212a*", "k\u00e9K")
window("empty_iter_window", "", "a\u00e9\u2603b", 1, 6)

section("Nullable loops and captures")
for name, pattern, text in [
    ("star_of_star", "(a*)*", "b"),
    ("star_of_star_consumes", "(a*)*", "aa"),
    ("plus_of_star", "(a*)+", "b"),
    ("plus_of_star_consumes", "(a*)+", "aa"),
    ("star_of_optional", "(a?)*", "aa"),
    ("star_of_lazy_star", "(a*?)*", "aa"),
    ("lazy_star_of_star", "(a*)*?", "aa"),
    ("plus_of_lazy_optional", "(a??)+", "aa"),
    ("star_of_empty_or_a", "(|a)*", "aa"),
    ("plus_of_empty_or_a", "(|a)+", "aa"),
    ("star_of_a_or_empty", "(a|)*", "aa"),
    ("plus_of_a_or_empty", "(a|)+", "aa"),
    ("star_of_empty_group", "()*", "a"),
    ("plus_of_empty_group", "()+", "a"),
    ("counted_empty_group", "(){3}", "a"),
    ("star_of_alternation_inner_groups", "((a)|b)*", "ab"),
    ("star_of_alternation_inner_groups_reversed", "((a)|b)*", "ba"),
    ("plus_of_alternation_groups", "(?:(a)|(b))+", "abab"),
    ("nested_star_groups", "((a*)b)*", "abb"),
    ("star_then_dollar", "(a*)+$", "aab"),
    ("counted_nullable", "(a?){2,3}", "a"),
    ("counted_nullable_exact", "(a?){3}", "aa"),
    ("group_empty_alternative_first", "(()|a)+", "aa"),
    ("group_inside_star_unset", "(?:x(a)?)*", "xax"),
    ("word_boundary_loop", "(\\b)*a", "a"),
    ("anchor_loop", "(^)*a", "a"),
    ("anchor_loop_after_start", "(^)*b", "ab"),
]:
    case(f"nullable_{name}", pattern, text)

section("Alternation preference")
for name, pattern, text in [
    ("shorter_first", "a|ab", "ab"),
    ("longer_first", "ab|a", "ab"),
    ("in_group_then_suffix", "(a|ab)(c|bcd)", "abcd"),
    ("in_star", "(?:a|ab)*c", "ababc"),
    ("in_star_captures", "(a|ab)*c", "ababc"),
    ("empty_first", "|a", "a"),
    ("empty_last", "a|", "a"),
    ("empty_middle", "x||a", "a"),
    ("lazy_vs_alternation", "(a+?|b)(a*)", "aaa"),
    ("greedy_prefix_backtracks", "(a*)(ab|b)", "aab"),
    ("leftmost_wins_over_longest", "b|abc", "abc"),
    ("case_insensitive_alternatives", "(?i)k|\u212a", "\u212a"),
    ("common_prefix", "samwise|sam", "samwise"),
    ("common_prefix_reversed", "sam|samwise", "samwise"),
    ("many_literals", "foo|foobar|bar|barfoo", "foobarfoo"),
]:
    case(f"alternation_{name}", pattern, text)

section("Windowed searches")
window("window_caret_after_start", "^a", "ba", 1, 2)
window("window_caret_multi_line_after_newline", "(?m)^a", "\na", 1, 2)
window("window_begin_text_after_start", "\\Aa", "ba", 1, 2)
window("window_dollar_before_end", "a$", "ab", 0, 1)
window("window_dollar_multi_line_before_newline", "(?m)a$", "a\n", 0, 1)
window("window_end_text_before_end", "a\\z", "ab", 0, 1)
window("window_word_boundary_left_context", "\\bb", "ab", 1, 2)
window("window_word_boundary_right_context", "a\\b", "ab", 0, 1)
window("window_not_word_boundary_left_context", "\\Bb", "ab", 1, 2)
window("window_word_boundary_multibyte_context", "\\b\u00e9", "a\u00e9", 1, 3)
window("window_ascii_word_boundary_multibyte_context", "(?-u:\\b)b", "\u00e9b", 2, 3)
window("window_word_start_left_context", "\\b{start}b", "ab", 1, 2)
window("window_word_end_right_context", "a\\b{end}", "ab", 0, 1)
window("window_word_start_half_left_context", "\\b{start-half}b", "ab", 1, 2)
window("window_literal_inside", "b+", "abbba", 1, 3)
window("window_literal_crossing_end", "abc", "abcabc", 1, 5)
window("window_empty_at_end", "", "abc", 3, 3)
window("window_empty_in_middle", "", "abc", 1, 1)
window("window_empty_pattern_star", "a*", "aaa", 1, 2)
window("window_multibyte_interior", "\u2603", "\u00e9\u2603\U0001F600", 2, 5)
window("window_dot_multibyte", ".", "\u00e9\u2603\U0001F600", 2, 9)
window("window_full_text", "^abc$", "abc", 0, 3)
window("window_full_match_interior", "b", "abc", 1, 2)
window("window_captures", "(a)(b)?", "xaby", 1, 2)
window("window_crlf_dot", "(?R).", "a\r\nb", 1, 3)
window("window_case_insensitive", "(?i)\u212a", "k\u212ak", 1, 4)

section("Octal option")
for name, pattern, text in [
    ("zero", "\\0", "a\x00"),
    ("seven", "\\7", "\x07"),
    ("two_digits", "\\10", "\x08\x01"),
    ("three_digits", "\\141", "xa"),
    ("max_byte", "\\377", "\u00ff"),
    ("above_byte", "\\400", "\u0100"),
    ("max", "\\777", "\u01ff"),
    ("four_digits", "\\1411", "a a1"),
    ("four_zeros", "\\0000", "\x00\x000"),
    ("zero_then_eight", "\\08", "\x008"),
    ("one_then_nine", "\\19", "\x019"),
    ("class_range", "[\\141-\\143]+", "abcd"),
    ("class_digits", "[\\0\\7]+", "\x00\x07\x01"),
    ("class_then_digit", "[\\1411]+", "a11b"),
    ("negated_class", "[^\\141]+", "bac"),
    ("repeated", "\\141{2}", "aaa"),
    ("after_group", "(a)\\1", "aa a\x01"),
    ("ascii", "(?-u:\\377)", "\u00ff"),
]:
    case(f"octal_{name}", pattern, text, "o")
case("octal_case_insensitive", "\\101", "a", "io")
case("octal_verbose_space", "\\1 41", "\x0141", "xo")

section("Bytes API: byte constructs")
# Options ending in "bytes" select regex::bytes::Regex. "\xFF" in Unicode mode
# is U+00FF, encoded as c3 bf.
for name, pattern, text in [
    ("hex_unicode", "\\xFF", b"\xff\xc3\xbf"),
    ("hex_byte", "(?-u:\\xFF)", b"\xff\xc3\xbf"),
    ("hex_byte_string", "(?-u:\\xFFa\\xFE)", b"a\xffa\xfe"),
    ("hex_byte_after_text", "ab(?-u:\\x80)", b"ab\x80ab\xc2\x80"),
    ("hex_braced_is_character", "(?-u:\\x{FF})", b"\xff\xc3\xbf"),
    ("hex_ascii", "(?-u:\\x7F)", b"\x7f"),
    ("mixed_byte_then_character", "(?-u:\\xFF)(?u:.)", b"\xff\xc3\xa9\xff\x80"),
    ("mixed_character_then_byte", ".(?-u:\\xA9)", b"\xc3\xa9\xa9"),
    ("mixed_literals", "\u00e9(?-u:\\xE9)\u00e9", b"\xc3\xa9\xe9\xc3\xa9"),
    ("mixed_word_run", "(?-u:\\xFF)\\w+", b"\xff\xc3\xa9t\xc3\xa9\xff"),
    ("dot", "(?-u:.)", b"\xff\n\xc3\xa9"),
    ("dot_all", "(?s-u:.)", b"\xff\n"),
    ("dot_crlf", "(?R-u:.)", b"\r\n\xff"),
    ("dot_plus", "(?-u:.+)", b"a\xffb\nc\x80"),
    ("dot_unicode", ".", b"a\xffb\xc3"),
    ("dot_all_unicode", "(?s).", b"\n\xff\xc3\xa9"),
    ("dot_star_unicode", "(?s).*", b"ab\xffcd"),
    ("dot_star_byte", "(?s-u).*", b"ab\xffcd"),
    ("dot_star_unicode_literal", "(?s).*c", b"ab\xffcd"),
    ("negated_class", "(?-u:[^a])+", b"a\xff\x80a\xc3\xa9"),
    ("negated_class_unicode", "[^a]+", b"a\xff\x80a\xc3\xa9"),
    ("byte_range", "(?-u:[\\x80-\\xFF])+", b"a\xc3\xa9\xffb"),
    ("byte_class_mixed", "(?-u:[a\\xE9])+", b"a\xe9\xc3\xa9"),
    ("byte_class_intersection", "(?-u:[[^a]&&[\\x00-\\x7F]])+", b"ab\xff\x7f"),
    ("byte_class_difference", "(?-u:[\\x00-\\xFF--a])+", b"ab\xff"),
    ("byte_class_symmetric_difference", "(?-u:[a-c~~[^b]])+", b"ab\xffc"),
    ("byte_class_nested_negation", "(?-u:[^[^\\xFF]])+", b"a\xff\xff"),
    ("ascii_class_negated", "(?-u:[[:^alpha:]])+", b"ab\xff1"),
    ("perl_non_word", "(?-u:\\W)+", b"ab \xff\xc3\xa9 1"),
    ("perl_non_space", "(?-u:\\S)+", b"ab \xff\xc3\xa9 1"),
    ("perl_non_digit", "(?-u:\\D)+", b"ab \xff\xc3\xa9 1"),
    ("perl_word", "(?-u:\\w)+", b"ab\xffcd"),
    ("perl_non_word_unicode", "\\W+", b"ab \xff\xc3\xa9 1"),
    ("case_insensitive_ascii", "(?i-u)k", b"kK\xe2\x84\xaa"),
    ("case_insensitive_byte", "(?i-u:\\xE9)", b"\xe9\xc9"),
    ("case_insensitive_class", "(?i-u)[a-c\\xE0-\\xFF]+", b"aBC\xe0\xc0"),
    ("case_insensitive_negated", "(?i-u)[^a]+", b"aA\xffb"),
    ("case_insensitive_unicode", "(?i)\u00e9", b"\xc3\xa9\xc3\x89\xe9"),
]:
    case(f"bytes_{name}", pattern, text, "bytes")
for name, pattern, text, options in [
    ("octal_is_character", "(?-u:\\377)", b"\xff\xc3\xbf", "o,bytes"),
    ("option_unicode_off_dot", ".", b"\xff\xc3\xa9", "-u,bytes"),
    ("option_unicode_off_negated", "[^a]+", b"a\xff\x80", "-u,bytes"),
    ("option_unicode_off_reenabled", "(?u:.)", b"\xff\xc3\xa9", "-u,bytes"),
    ("option_case_insensitive", "\\xE9", b"\xe9\xc9", "i,-u,bytes"),
    ("option_dot_all", ".", b"\n\xff", "s,-u,bytes"),
]:
    case(f"bytes_{name}", pattern, text, options)

section("Bytes API: Unicode constructs on malformed text")
for name, pattern, text in [
    ("dot_lone_continuation", ".", b"\x80a"),
    ("dot_truncated", ".+", b"\xe2\x98a"),
    ("dot_overlong_two", "(?s).", b"\xc0\x80\xc1\xbfa"),
    ("dot_overlong_three", "(?s).", b"\xe0\x80\x80a"),
    ("dot_overlong_four", "(?s).", b"\xf0\x80\x80\x80a"),
    ("dot_surrogate", "(?s).", b"\xed\xa0\x80a"),
    ("dot_above_max", "(?s).", b"\xf4\x90\x80\x80a"),
    ("dot_max", "(?s).", b"\xf4\x8f\xbf\xbf"),
    ("dot_invalid_lead", "(?s).", b"\xf5\x80\x80\x80\xffa"),
    ("negated_class_surrogate", "[^a]", b"\xed\xa0\x80\xed\x9f\xbf"),
    ("negated_class_overlong", "[^a]", b"\xe0\x9f\xbf\xe0\xa0\x80"),
    ("wide_range", "[\\x{80}-\\x{10FFFF}]+", b"\xc0\x80\xe0\x80\x80\xc3\xa9\xf4\x90\x80\x80"),
    ("property_letter", "\\pL+", b"ab\xffcd\xc3\xa9"),
    ("word", "\\w+", b"ab\xffcd"),
    ("non_space", "\\S+", b"ab\x80\xff cd"),
    ("digit_class", "\\d+", b"1\xff2"),
    ("literal_split", "\u00e9", b"\xc3\xc3\xa9\xa9"),
    ("case_insensitive_kelvin", "(?i)k+", b"k\xe2\x84\xaaK\xe2\x84"),
    ("dot_star_suffix", "(?s).*b", b"a\xffb"),
    ("dot_plus_prefix", "a.+", b"a\xffbac"),
    ("alternation", "\u00e9|(?-u:\\xC3)", b"\xc3\xc3\xa9"),
]:
    case(f"bytes_malformed_{name}", pattern, text, "bytes")

section("Bytes API: empty matches")
for name, pattern, text in [
    ("empty_pattern", "", "\u00e9"),
    ("empty_pattern_mixed", "", "a\u2603\U0001F600"),
    ("empty_pattern_invalid", "", b"\xff\x80"),
    ("star_absent", "x*", "\u00e9\u2603"),
    ("star_mixed", "a*", "a\u00e9a"),
    ("optional_character", "(\u00e9)?", "x\u00e9x"),
    ("after_nonempty", "\u2603|", "a\u2603b"),
    ("empty_first", "|\u2603", "a\u2603b"),
    ("lazy_star", "\u00e9*?", "\u00e9\u00e9"),
    ("byte_star", "(?-u:\\xA9)*", "\u00e9\u00e9"),
    ("ascii_word_boundary", "(?-u:\\b)", "a\u00e9b"),
    ("not_ascii_word_boundary", "(?-u:\\B)", "\u00e9\u2603"),
    ("ascii_word_start_half", "(?-u:\\b{start-half})", "\u00e9a"),
    ("ascii_word_end_half", "(?-u:\\b{end-half})", "a\u00e9"),
    ("word_boundary", "\\b", "\u00e9 \u00e9"),
    ("not_word_boundary", "\\B", "\u00e9 \u00e9"),
    ("word_start", "\\b{start}", "\u00e9a b"),
]:
    case(f"bytes_empty_iter_{name}", pattern, text, "bytes")
window("bytes_empty_iter_window_split", "", "\u00e9", 1, 2, "bytes")
window("bytes_empty_iter_window_split_end", "", "\u00e9a", 0, 1, "bytes")
window("bytes_empty_iter_window_inside", "", "a\u00e9\u2603b", 2, 5, "bytes")

section("Bytes API: word boundaries next to malformed text")
for name, pattern, text in [
    ("after_word", "\\b", b"a\x80"),
    ("before_word", "\\b", b"\x80a"),
    ("run", "\\b\\w+\\b", b"\xffabc\xff"),
    ("split_word", "\\b\\w+\\b", b"\xc3\xa9\xa9ab"),
    ("ascii_run_after_stray", "\\b[a-z]+\\b", b"a\x80b c\xff"),
    ("not_word_invalid", "\\B", b"\x80\x80"),
    ("not_word_stray_continuation", "\\B", b"a\x80"),
    ("not_word_after_stray_continuation", "\\B", b"a\x80b"),
    ("not_word_trailing_stray", "\\B", "\u00e9".encode() + b"\xa9"),
    ("not_word_truncated", "\\B", b"\xe2\x98"),
    ("not_word_overlong", "\\B", b"\xc0\x80"),
    ("word_start", "\\b{start}", b"\x80a\x80"),
    ("word_end", "\\b{end}", b"\x80a\x80"),
    ("word_start_half", "\\b{start-half}", b"\x80a\x80"),
    ("word_end_half", "\\b{end-half}", b"\x80a\x80"),
    ("word_start_half_valid", "\\b{start-half}", b"a b"),
    ("word_start_angle", "\\<\\w", b"\xffa\xc3\xa9"),
    ("word_end_angle", "\\w\\>", b"a\xff\xc3\xa9\xa9"),
    ("ascii", "(?-u:\\b)", b"a\xffb"),
    ("ascii_not_word", "(?-u:\\B)", b"\xff\xfe"),
    ("ascii_word_start", "(?-u:\\b{start})", b"\xffa\xc3\xa9"),
    ("ascii_word_end", "(?-u:\\b{end})", b"a\xa9"),
    ("ascii_word_start_half", "(?-u:\\b{start-half})", b"\xa9a"),
    ("ascii_word_end_half", "(?-u:\\b{end-half})", b"a\xa9"),
    ("ascii_byte_run", "(?-u:\\b\\w+\\b)", b"\xc3\xa9ab\xff"),
]:
    case(f"bytes_boundary_{name}", pattern, text, "bytes")

section("Bytes API: line anchors")
for name, pattern, text in [
    ("multi_line_byte_lines", "(?m-u)^.+$", b"\xff\n\xfe\n"),
    ("multi_line_crlf", "(?mR-u)^.*$", b"\xff\r\n\xfe\r\x80"),
    ("multi_line_crlf_dollar", "(?mR)$", b"\xff\r\n\r"),
    ("multi_line_crlf_caret", "(?mR)^", b"\r\n\xff\r\n"),
    ("multi_line_byte_before_dollar", "(?m)(?-u:\\xFF)$", b"\xff\n\xff"),
    ("end_text", "(?-u:\\xFF)$", b"\xff\xff"),
    ("begin_text", "^(?-u:\\xFF)", b"\xff\xff"),
    ("multi_line_empty_lines", "(?m)^$", b"\n\xff\n\n"),
]:
    case(f"bytes_line_{name}", pattern, text, "bytes")

section("Bytes API: windows")
window("bytes_window_byte_at_split", "(?-u:\\xA9)", "\u00e9", 1, 2, "bytes")
window("bytes_window_byte_dot_at_split", "(?-u:.)", "\u00e9\u2603", 1, 4, "bytes")
window("bytes_window_unicode_dot_at_split", ".", "\u00e9\u2603", 1, 5, "bytes")
window("bytes_window_split_end", "\u00e9", "\u00e9\u00e9", 0, 3, "bytes")
window("bytes_window_word_boundary_at_split", "\\b", "a\u00e9", 2, 3, "bytes")
window("bytes_window_not_word_boundary_at_split", "\\B", "a\u00e9", 2, 3, "bytes")
window("bytes_window_word_start_half_at_split", "\\b{start-half}", "\u00e9", 1, 2, "bytes")
window("bytes_window_word_end_half_at_split", "\\b{end-half}", "\u00e9", 0, 1, "bytes")
window("bytes_window_caret_at_split", "(?m)^", "\u00e9", 1, 2, "bytes")
window("bytes_window_captures", "((?-u:.))(.)", "\u00e9\u2603", 1, 5, "bytes")
window("bytes_window_malformed", "\\w+", b"a\xffbc\x80", 1, 4, "bytes")
window("bytes_window_empty_split", "x*", "\u2603", 1, 3, "bytes")

section("Bytes API: Rebar benchmark patterns")
# Rebar patterns that run without Unicode mode, so only the bytes API accepts
# them, on short texts in the shape of their haystacks. The patterns are copied
# from Rebar commit 463d00f31887e84c38467805b9e3122c314b9521, the pin in
# tools/re2-benchmark/rebar/README.md. Their workload identities, from
# tools/re2-benchmark/manifests/rebar-workloads.csv and
# rebar-extended-workloads.tsv, are:
# - unicode_data_line: curated/07-unicode-character-data/parse-line
# - fn_predicate: opt/onepass/fn-predicate
# - i13_huge: reported/i13-subset-regex/huge-ascii-nosuffixlit
# - i13_original: reported/i13-subset-regex/original-ascii
# - url: wild/url/search, which also runs case-insensitively
rebar_url = (
    '((?:(?:(?:https?|ftp)://(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4]'
    '[0-9]|[01]?[0-9][0-9]?))|(?:(?:https?|ftp)://)?(?:[a-z0-9%.]+:[a-z0-9%]+@)?(?:(?:[a-z0-9_~'
    ']\\-?){0,62}[a-z0-9]\\.)*(?:(?:(?:[a-z0-9]\\-?){0,62}[a-z0-9])|(?:xn--[a-z0-9\\-]+))\\.(?:XN--V'
    'ERMGENSBERATUNG-PWB|XN--VERMGENSBERATER-CTB|XN--CLCHC0EA0B2G2A9GCD|XN--W4R85EL8FHU5DNRA|NO'
    'RTHWESTERNMUTUAL|TRAVELERSINSURANCE|XN--3OQ18VL8PN36A|XN--5SU34J936BGSG|XN--BCK1B9A5DRE4C|'
    'XN--MGBAH1A3HJKRD|XN--MGBAI9AZGQP6J|XN--MGBERP4A5D4AR|XN--XKC2DL3A5EE0H|XN--FZYS8D69UVGM|X'
    'N--MGBA7C0BBN0A|XN--MGBCPQ6GPA1A|XN--XKC2AL3HYE2A|AMERICANEXPRESS|KERRYPROPERTIES|SANDVIKC'
    'OROMANT|XN--I1B6B1A6A2E|XN--KCRX77D1X4A|XN--LGBBAT1AD8J|XN--MGBA3A4F16A|XN--MGBAAKC7DVF|XN'
    '--MGBC0A9AZCG|XN--NQV7FS00EMA|AFAMILYCOMPANY|AMERICANFAMILY|BANANAREPUBLIC|CANCERRESEARCH|'
    'COOKINGCHANNEL|KERRYLOGISTICS|WEATHERCHANNEL|XN--54B7FTA0CC|XN--6QQ986B3XL|XN--80AQECDR1A|'
    'XN--B4W605FERD|XN--FIQ228C5HS|XN--H2BREG3EVE|XN--JLQ480N2RG|XN--JLQ61U9W7B|XN--MGBA3A3EJT|'
    'XN--MGBAAM7A8H|XN--MGBAYH7GPA|XN--MGBBH1A71E|XN--MGBCA7DZDO|XN--MGBI4ECEXP|XN--MGBX4CD0AB|'
    'XN--RVC1E0AM3E|INTERNATIONAL|LIFEINSURANCE|TRAVELCHANNEL|WOLTERSKLUWER|XN--CCKWCXETD|XN--E'
    'CKVDTC9D|XN--FPCRJ9C3D|XN--FZC2C9E2C|XN--H2BRJ9C8C|XN--TIQ49XQYJ|XN--YFRO4I67O|XN--YGBI2AM'
    'MX|CONSTRUCTION|LPLFINANCIAL|SCHOLARSHIPS|VERSICHERUNG|XN--3E0B707E|XN--45BR5CYL|XN--4DBRK'
    '0CE|XN--80ADXHKS|XN--80ASEHDB|XN--8Y0A063A|XN--GCKR3F0F|XN--MGB9AWBF|XN--MGBAB2BD|XN--MGBG'
    'U82A|XN--MGBPL2FH|XN--MGBT3DHD|XN--MK1BU44C|XN--NGBC5AZD|XN--NGBE9E0A|XN--OGBPF8FL|XN--QCK'
    'A1PMC|ACCOUNTANTS|BARCLAYCARD|BLACKFRIDAY|BLOCKBUSTER|BRIDGESTONE|CALVINKLEIN|CONTRACTORS|'
    'CREDITUNION|ENGINEERING|ENTERPRISES|FOODNETWORK|INVESTMENTS|KERRYHOTELS|LAMBORGHINI|MOTORC'
    'YCLES|OLAYANGROUP|PHOTOGRAPHY|PLAYSTATION|PRODUCTIONS|PROGRESSIVE|REDUMBRELLA|WILLIAMHILL|'
    'XN--11B4C3D|XN--1CK2E1B|XN--1QQW23A|XN--2SCRJ9C|XN--3BST00M|XN--3DS443G|XN--3HCRJ9C|XN--42'
    'C2D9A|XN--45BRJ9C|XN--55QW42G|XN--6FRZ82G|XN--80AO21A|XN--9KRT00A|XN--CCK2B3B|XN--CZR694B|'
    'XN--D1ACJ3B|XN--EFVY88H|XN--FCT429K|XN--FJQ720A|XN--FLW351E|XN--G2XX48C|XN--GECRJ9C|XN--GK'
    '3AT1E|XN--H2BRJ9C|XN--HXT814E|XN--IMR513N|XN--J6W193G|XN--JVR189M|XN--KPRW13D|XN--KPRY57D|'
    'XN--MGBBH1A|XN--MGBTX2B|XN--MIX891F|XN--NYQY26A|XN--OTU796D|XN--PGBS0DH|XN--Q9JYB4C|XN--RH'
    'QV96G|XN--ROVU88B|XN--S9BRJ9C|XN--SES554G|XN--T60B56A|XN--VUQ861B|XN--W4RS40L|XN--XHQ521B|'
    'XN--ZFR164B|ACCOUNTANT|APARTMENTS|ASSOCIATES|BASKETBALL|BNPPARIBAS|BOEHRINGER|CAPITALONE|C'
    'ONSULTING|CREDITCARD|CUISINELLA|EUROVISION|EXTRASPACE|FOUNDATION|HEALTHCARE|IMMOBILIEN|IND'
    'USTRIES|MANAGEMENT|MITSUBISHI|NEXTDIRECT|PROPERTIES|PROTECTION|PRUDENTIAL|REALESTATE|REPUB'
    'LICAN|RESTAURANT|SCHAEFFLER|SWIFTCOVER|TATAMOTORS|TECHNOLOGY|UNIVERSITY|VLAANDEREN|VOLKSWA'
    'GEN|XN--30RR7Y|XN--3PXU8K|XN--45Q11C|XN--4GBRIM|XN--55QX5D|XN--5TZM5G|XN--80ASWG|XN--90A3A'
    'C|XN--9DBQ2A|XN--9ET52U|XN--C2BR7G|XN--CG4BKI|XN--CZRS0T|XN--CZRU2D|XN--FIQ64B|XN--FIQS8S|'
    'XN--FIQZ9S|XN--IO0A7I|XN--KPUT3I|XN--MXTQ1M|XN--O3CW4H|XN--PSSY2U|XN--Q7CE6A|XN--UNUP4Y|XN'
    '--WGBH1C|XN--WGBL6A|XN--Y9A3AQ|ACCENTURE|ALFAROMEO|ALLFINANZ|AMSTERDAM|ANALYTICS|AQUARELLE'
    '|BARCELONA|BLOOMBERG|CHRISTMAS|COMMUNITY|DIRECTORY|EDUCATION|EQUIPMENT|FAIRWINDS|FINANCIAL'
    '|FIRESTONE|FRESENIUS|FRONTDOOR|FURNITURE|GOLDPOINT|HISAMITSU|HOMEDEPOT|HOMEGOODS|HOMESENSE'
    '|INSTITUTE|INSURANCE|KUOKGROUP|LANCASTER|LANDROVER|LIFESTYLE|MARKETING|MARSHALLS|MELBOURNE'
    '|MICROSOFT|PANASONIC|PASSAGENS|PRAMERICA|RICHARDLI|SCJOHNSON|SHANGRILA|SOLUTIONS|STATEBANK'
    '|STATEFARM|STOCKHOLM|TRAVELERS|VACATIONS|XN--90AIS|XN--C1AVG|XN--D1ALF|XN--E1A4C|XN--FHBEI'
    '|XN--J1AEF|XN--J1AMH|XN--L1ACC|XN--NGBRX|XN--NQV7F|XN--P1ACF|XN--QXA6A|XN--TCKWE|XN--VHQUV'
    '|YODOBASHI|ABUDHABI|AIRFORCE|ALLSTATE|ATTORNEY|BARCLAYS|BAREFOOT|BARGAINS|BASEBALL|BOUTIQU'
    'E|BRADESCO|BROADWAY|BRUSSELS|BUDAPEST|BUILDERS|BUSINESS|CAPETOWN|CATERING|CATHOLIC|CIPRIAN'
    'I|CITYEATS|CLEANING|CLINIQUE|CLOTHING|COMMBANK|COMPUTER|DELIVERY|DELOITTE|DEMOCRAT|DIAMOND'
    'S|DISCOUNT|DISCOVER|DOWNLOAD|ENGINEER|ERICSSON|ETISALAT|EXCHANGE|FEEDBACK|FIDELITY|FIRMDAL'
    'E|FOOTBALL|FRONTIER|GOODYEAR|GRAINGER|GRAPHICS|GUARDIAN|HDFCBANK|HELSINKI|HOLDINGS|HOSPITA'
    'L|INFINITI|IPIRANGA|ISTANBUL|JPMORGAN|LIGHTING|LUNDBECK|MARRIOTT|MASERATI|MCKINSEY|MEMORIA'
    'L|MERCKMSD|MORTGAGE|OBSERVER|PARTNERS|PHARMACY|PICTURES|PLUMBING|PROPERTY|REDSTONE|RELIANC'
    'E|SAARLAND|SAMSCLUB|SECURITY|SERVICES|SHOPPING|SHOWTIME|SOFTBANK|SOFTWARE|STCGROUP|SUPPLIE'
    'S|TRAINING|VANGUARD|VENTURES|VERISIGN|WOODSIDE|XN--90AE|XN--NODE|XN--P1AI|XN--QXAM|YOKOHAM'
    'A|ABOGADO|ACADEMY|AGAKHAN|ALIBABA|ANDROID|ATHLETA|AUCTION|AUDIBLE|AUSPOST|AVIANCA|BANAMEX|'
    'BAUHAUS|BENTLEY|BESTBUY|BOOKING|BROTHER|BUGATTI|CAPITAL|CARAVAN|CAREERS|CHANNEL|CHARITY|CH'
    'INTAI|CITADEL|CLUBMED|COLLEGE|COLOGNE|COMCAST|COMPANY|COMPARE|CONTACT|COOKING|CORSICA|COUN'
    'TRY|COUPONS|COURSES|CRICKET|CRUISES|DENTIST|DIGITAL|DOMAINS|EXPOSED|EXPRESS|FARMERS|FASHIO'
    'N|FERRARI|FERRERO|FINANCE|FISHING|FITNESS|FLIGHTS|FLORIST|FLOWERS|FORSALE|FROGANS|FUJITSU|'
    'GALLERY|GENTING|GODADDY|GROCERY|GUITARS|HAMBURG|HANGOUT|HITACHI|HOLIDAY|HOSTING|HOTELES|HO'
    'TMAIL|HYUNDAI|ISMAILI|JEWELRY|JUNIPER|KITCHEN|KOMATSU|LACAIXA|LANXESS|LASALLE|LATROBE|LECL'
    'ERC|LIMITED|LINCOLN|MARKETS|MONSTER|NETBANK|NETFLIX|NETWORK|NEUSTAR|OKINAWA|OLDNAVY|ORGANI'
    'C|ORIGINS|PHILIPS|PIONEER|POLITIE|REALTOR|RECIPES|RENTALS|REVIEWS|REXROTH|SAMSUNG|SANDVIK|'
    'SCHMIDT|SCHWARZ|SCIENCE|SHIKSHA|SINGLES|STAPLES|STORAGE|SUPPORT|SURGERY|SYSTEMS|TEMASEK|TH'
    'EATER|THEATRE|TICKETS|TIFFANY|TOSHIBA|TRADING|WALMART|WANGGOU|WATCHES|WEATHER|WEBSITE|WEDD'
    'ING|WHOSWHO|WINDOWS|WINNERS|XFINITY|YAMAXUN|YOUTUBE|ZUERICH|ABARTH|ABBOTT|ABBVIE|AFRICA|AG'
    'ENCY|AIRBUS|AIRTEL|ALIPAY|ALSACE|ALSTOM|AMAZON|ANQUAN|ARAMCO|AUTHOR|BAYERN|BEAUTY|BERLIN|B'
    'HARTI|BOSTIK|BOSTON|BROKER|CAMERA|CAREER|CASINO|CENTER|CHANEL|CHROME|CHURCH|CIRCLE|CLAIMS|'
    'CLINIC|COFFEE|COMSEC|CONDOS|COUPON|CREDIT|CRUISE|DATING|DATSUN|DEALER|DEGREE|DENTAL|DESIGN'
    '|DIRECT|DOCTOR|DUNLOP|DUPONT|DURBAN|EMERCK|ENERGY|ESTATE|EVENTS|EXPERT|FAMILY|FLICKR|FUTBO'
    'L|GALLUP|GARDEN|GEORGE|GIVING|GLOBAL|GOOGLE|GRATIS|HEALTH|HERMES|HIPHOP|HOCKEY|HOTELS|HUGH'
    'ES|IMAMAT|INSURE|INTUIT|JAGUAR|JOBURG|JUEGOS|KAUFEN|KINDER|KINDLE|KOSHER|LANCIA|LATINO|LAW'
    'YER|LEFRAK|LIVING|LOCKER|LONDON|LUXURY|MADRID|MAISON|MAKEUP|MARKET|MATTEL|MOBILE|MONASH|MO'
    'RMON|MOSCOW|MUSEUM|MUTUAL|NAGOYA|NATURA|NISSAN|NISSAY|NORTON|NOWRUZ|OFFICE|OLAYAN|ONLINE|O'
    'RACLE|ORANGE|OTSUKA|PFIZER|PHOTOS|PHYSIO|PICTET|QUEBEC|RACING|REALTY|REISEN|REPAIR|REPORT|'
    'REVIEW|ROCHER|ROGERS|RYUKYU|SAFETY|SAKURA|SANOFI|SCHOOL|SCHULE|SEARCH|SECURE|SELECT|SHOUJI'
    '|SOCCER|SOCIAL|STREAM|STUDIO|SUPPLY|SUZUKI|SWATCH|SYDNEY|TAIPEI|TAOBAO|TARGET|TATTOO|TENNI'
    'S|TIENDA|TJMAXX|TKMAXX|TOYOTA|TRAVEL|UNICOM|VIAJES|VIKING|VILLAS|VIRGIN|VISION|VOTING|VOYA'
    'GE|VUELOS|WALTER|WEBCAM|XIHUAN|YACHTS|YANDEX|ZAPPOS|ACTOR|ADULT|AETNA|AMFAM|AMICA|APPLE|AR'
    'CHI|AUDIO|AUTOS|AZURE|BAIDU|BEATS|BIBLE|BINGO|BLACK|BOATS|BOSCH|BUILD|CANON|CARDS|CHASE|CH'
    'EAP|CISCO|CITIC|CLICK|CLOUD|COACH|CODES|CROWN|CYMRU|DABUR|DANCE|DEALS|DELTA|DRIVE|DUBAI|EA'
    'RTH|EDEKA|EMAIL|EPSON|FAITH|FEDEX|FINAL|FOREX|FORUM|GALLO|GAMES|GIFTS|GIVES|GLADE|GLASS|GL'
    'OBO|GMAIL|GREEN|GRIPE|GROUP|GUCCI|GUIDE|HOMES|HONDA|HORSE|HOUSE|HYATT|IKANO|IRISH|JETZT|KO'
    'ELN|KYOTO|LAMER|LEASE|LEGAL|LEXUS|LILLY|LINDE|LIPSY|LIXIL|LOANS|LOCUS|LOTTE|LOTTO|MACYS|MA'
    'NGO|MEDIA|MIAMI|MONEY|MOVIE|NEXUS|NIKON|NINJA|NOKIA|NOWTV|OMEGA|OSAKA|PARIS|PARTS|PARTY|PH'
    'ONE|PHOTO|PIZZA|PLACE|POKER|PRAXI|PRESS|PRIME|PROMO|QUEST|RADIO|REHAB|REISE|RICOH|ROCKS|RO'
    'DEO|RUGBY|SALON|SENER|SEVEN|SHARP|SHELL|SHOES|SKYPE|SLING|SMART|SMILE|SOLAR|SPACE|SPORT|ST'
    'ADA|STORE|STUDY|STYLE|SUCKS|SWISS|TATAR|TIRES|TIROL|TMALL|TODAY|TOKYO|TOOLS|TORAY|TOTAL|TO'
    'URS|TRADE|TRUST|TUNES|TUSHU|UBANK|VEGAS|VIDEO|VODKA|VOLVO|WALES|WATCH|WEBER|WEIBO|WORKS|WO'
    'RLD|XEROX|YAHOO|AARP|ABLE|ADAC|AERO|AKDN|ALLY|AMEX|ARAB|ARMY|ARPA|ARTE|ASDA|ASIA|AUDI|AUTO'
    '|BABY|BAND|BANK|BBVA|BEER|BEST|BIKE|BING|BLOG|BLUE|BOFA|BOND|BOOK|BUZZ|CAFE|CALL|CAMP|CARE'
    '|CARS|CASA|CASE|CASH|CBRE|CERN|CHAT|CITI|CITY|CLUB|COOL|COOP|CYOU|DATA|DATE|DCLK|DEAL|DELL'
    '|DESI|DIET|DISH|DOCS|DUCK|DVAG|ERNI|FAGE|FAIL|FANS|FARM|FAST|FIAT|FIDO|FILM|FIRE|FISH|FLIR'
    '|FOOD|FORD|FREE|FUND|GAME|GBIZ|GENT|GGEE|GIFT|GMBH|GOLD|GOLF|GOOG|GUGE|GURU|HAIR|HAUS|HDFC'
    '|HELP|HERE|HGTV|HOST|HSBC|ICBC|IEEE|IMDB|IMMO|INFO|ITAU|JAVA|JEEP|JOBS|JPRS|KDDI|KIWI|KPMG'
    '|KRED|LAND|LEGO|LGBT|LIDL|LIFE|LIKE|LIMO|LINK|LIVE|LOAN|LOFT|LOVE|LTDA|LUXE|MAIF|MEET|MEME'
    '|MENU|MINI|MINT|MOBI|MODA|MOTO|NAME|NAVY|NEWS|NEXT|NICO|NIKE|OLLO|OPEN|PAGE|PARS|PCCW|PICS'
    '|PING|PINK|PLAY|PLUS|POHL|PORN|POST|PROD|PROF|QPON|RAID|READ|REIT|RENT|REST|RICH|RMIT|ROOM'
    '|RSVP|RUHR|SAFE|SALE|SARL|SAVE|SAXO|SCOT|SEAT|SEEK|SEXY|SHAW|SHIA|SHOP|SHOW|SILK|SINA|SITE'
    '|SKIN|SNCF|SOHU|SONG|SONY|SPOT|STAR|SURF|TALK|TAXI|TEAM|TECH|TEVA|TIAA|TIPS|TOWN|TOYS|TUBE'
    '|VANA|VISA|VIVA|VIVO|VOTE|VOTO|WANG|WEIR|WIEN|WIKI|WINE|WORK|XBOX|YOGA|ZARA|ZERO|ZONE|AAA|'
    'ABB|ABC|ACO|ADS|AEG|AFL|AIG|ANZ|AOL|APP|ART|AWS|AXA|BAR|BBC|BBT|BCG|BCN|BET|BID|BIO|BIZ|BM'
    'S|BMW|BOM|BOO|BOT|BOX|BUY|BZH|CAB|CAL|CAM|CAR|CAT|CBA|CBN|CBS|CEO|CFA|CFD|COM|CPA|CRS|CSC|'
    'DAD|DAY|DDS|DEV|DHL|DIY|DNP|DOG|DOT|DTV|DVR|EAT|ECO|EDU|ESQ|EUS|FAN|FIT|FLY|FOO|FOX|FRL|FT'
    'R|FUN|FYI|GAL|GAP|GAY|GDN|GEA|GLE|GMO|GMX|GOO|GOP|GOT|GOV|HBO|HIV|HKT|HOT|HOW|IBM|ICE|ICU|'
    'IFM|INC|ING|INK|INT|IST|ITV|JCB|JIO|JLL|JMP|JNJ|JOT|JOY|KFH|KIA|KIM|KPN|KRD|LAT|LAW|LDS|LL'
    'C|LLP|LOL|LPL|LTD|MAN|MAP|MBA|MED|MEN|MIL|MIT|MLB|MLS|MMA|MOE|MOI|MOM|MOV|MSD|MTN|MTR|NAB|'
    'NBA|NEC|NET|NEW|NFL|NGO|NHK|NOW|NRA|NRW|NTT|NYC|OBI|OFF|ONE|ONG|ONL|OOO|ORG|OTT|OVH|PAY|PE'
    'T|PHD|PID|PIN|PNC|PRO|PRU|PUB|PWC|QVC|RED|REN|RIL|RIO|RIP|RUN|RWE|SAP|SAS|SBI|SBS|SCA|SCB|'
    'SES|SEW|SEX|SFR|SKI|SKY|SOY|SPA|SRL|STC|TAB|TAX|TCI|TDK|TEL|THD|TJX|TOP|TRV|TUI|TVS|UBS|UN'
    'O|UOL|UPS|VET|VIG|VIN|VIP|WED|WIN|WME|WOW|WTC|WTF|XIN|XXX|XYZ|YOU|YUN|ZIP|AC|AD|AE|AF|AG|A'
    'I|AL|AM|AO|AQ|AR|AS|AT|AU|AW|AX|AZ|BA|BB|BD|BE|BF|BG|BH|BI|BJ|BM|BN|BO|BR|BS|BT|BV|BW|BY|B'
    'Z|CA|CC|CD|CF|CG|CH|CI|CK|CL|CM|CN|CO|CR|CU|CV|CW|CX|CY|CZ|DE|DJ|DK|DM|DO|DZ|EC|EE|EG|ER|E'
    'S|ET|EU|FI|FJ|FK|FM|FO|FR|GA|GB|GD|GE|GF|GG|GH|GI|GL|GM|GN|GP|GQ|GR|GS|GT|GU|GW|GY|HK|HM|H'
    'N|HR|HT|HU|ID|IE|IL|IM|IN|IO|IQ|IR|IS|IT|JE|JM|JO|JP|KE|KG|KH|KI|KM|KN|KP|KR|KW|KY|KZ|LA|L'
    'B|LC|LI|LK|LR|LS|LT|LU|LV|LY|MA|MC|MD|ME|MG|MH|MK|ML|MM|MN|MO|MP|MQ|MR|MS|MT|MU|MV|MW|MX|M'
    'Y|MZ|NA|NC|NE|NF|NG|NI|NL|NO|NP|NR|NU|NZ|OM|PA|PE|PF|PG|PH|PK|PL|PM|PN|PR|PS|PT|PW|PY|QA|R'
    'E|RO|RS|RU|RW|SA|SB|SC|SD|SE|SG|SH|SI|SJ|SK|SL|SM|SN|SO|SR|SS|ST|SU|SV|SX|SY|SZ|TC|TD|TF|T'
    'G|TH|TJ|TK|TL|TM|TN|TO|TR|TT|TV|TW|TZ|UA|UG|UK|US|UY|UZ|VA|VC|VE|VG|VI|VN|VU|WF|WS|YE|YT|Z'
    "A|ZM|ZW))(?::\\d{2,5})?(?:/[a-z0-9/\\-_%$@&()!?'=~*+:;,.]+)*/?(?:[?#]\\S*)*/?)"
)
rebar_unicode_data_line = (
    r"^([A-Z0-9]+);([^;]+);([^;]+);([0-9]+);([^;]+);([^;]*);([0-9]*);([0-9]*);([-0-9/]*);([YN]);"
    r"([^;]*);([^;]*);([^;]*);([^;]*);([^;]*)$")
rebar_fn_predicate = r"^\s*fn\s+(is_([^\(]+))\(([^)]+)\) -> bool \{$"
for name, pattern, text, options in [
    ("unicode_data_line", rebar_unicode_data_line,
     b"00E9;LATIN SMALL LETTER E WITH ACUTE;Ll;0;L;0065 0301;;;;N;LATIN SMALL LETTER E ACUTE;;00C9;;00C9", "-u,bytes"),
    ("unicode_data_line_bytes", rebar_unicode_data_line,
     "00E9;LATIN \u00e9 LETTER;Ll;0;L;;;;;N;;;00C9;;00C9".encode() + b"\xff", "-u,bytes"),
    ("unicode_data_line_short", rebar_unicode_data_line, b"0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;0061;", "-u,bytes"),
    ("fn_predicate", rebar_fn_predicate, b"    fn is_word_byte(b: u8) -> bool {", "-u,bytes"),
    ("fn_predicate_bytes", rebar_fn_predicate, b"\tfn is_\xc3\xa9\xff(x: \xff) -> bool {", "-u,bytes"),
    ("i13_huge", r"[a-q][^u-z]{80}[x\xE0-\xFF]", b"a" + b"0123456789" * 8 + b"\xe9" + b"b" + b"\xc3\xa9" * 40 + b"\xff", "-u,bytes"),
    ("i13_huge_utf8", r"[a-q][^u-z]{80}[x\xE0-\xFF]", ("q" + "\u00e9" * 40 + "x").encode(), "-u,bytes"),
    ("i13_original", r"[a-q][^u-z]{13}x", b"abc\xff\xc3\xa9defghijklmx a0123456789abcx", "-u,bytes"),
    ("url", rebar_url, b"see https://www.Example.COM/path?q=1 and ftp://192.168.0.1/x \xff http://xn--bcher-kva.example/ end", "i,-u,bytes"),
    ("url_bytes", rebar_url, b"\xffwww.rust-lang.org\xff/\xe9 user:pw@host.io:8080/a%20b#frag\xc3\xa9", "i,-u,bytes"),
]:
    case(f"bytes_rebar_{name}", pattern, text, options)
