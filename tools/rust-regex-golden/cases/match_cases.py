# Readable source for ../match-cases.tsv. Run emit.py after editing.
#
# Each case is (id, pattern, options, text, start, end). Patterns and texts are
# Python strings, encoded as UTF-8 hex by emit.py. Options use the encoding in
# ../README.md. A window is a pair of byte offsets into the UTF-8 text; "-"
# means the whole text.
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
