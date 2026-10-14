# Readable source for ../syntax-cases.tsv. Run emit.py after editing.
#
# Each case is (id, pattern, options). Patterns are Python strings, encoded as
# UTF-8 hex by emit.py. Options use the encoding in ../README.md.
cases = []


def section(title):
    cases.append(("#", title))


def case(id, pattern, options="-"):
    cases.append((id, pattern, options))


section("Literals and escapes")
case("literal_non_ascii", "\u00e9\u2603\U0001F600")
for name, escape in [("bell", r"\a"), ("form_feed", r"\f"), ("tab", r"\t"), ("newline", r"\n"),
                     ("carriage_return", r"\r"), ("vertical_tab", r"\v")]:
    case(f"escape_{name}", escape)
case("escape_hex_two_digits", r"\x7F")
case("escape_hex_two_digits_lowercase", r"\x7f")
case("escape_hex_two_digits_non_ascii", r"\xE9")
case("escape_hex_one_digit_at_end", r"\x7")
case("escape_hex_one_digit_then_literal", r"\x7z")
case("escape_hex_invalid_digit", r"\xZZ")
case("escape_hex_missing_digits", r"\x")
case("escape_hex_braced_max", r"\x{10FFFF}")
case("escape_hex_braced_one_digit", r"\x{7}")
case("escape_hex_braced_leading_zeros", r"\x{0000000041}")
case("escape_hex_braced_out_of_range", r"\x{110000}")
case("escape_hex_braced_surrogate_first", r"\x{D800}")
case("escape_hex_braced_surrogate_last", r"\x{DFFF}")
case("escape_hex_braced_empty", r"\x{}")
case("escape_hex_braced_unclosed", r"\x{41")
case("escape_hex_braced_invalid_digit", r"\x{4G}")
case("escape_hex_braced_space", r"\x{ 41 }")
case("escape_hex_braced_space_verbose", r"\x{ 41 }", "x")
case("escape_unicode_four_digits", r"\u007F")
case("escape_unicode_four_digits_too_few", r"\u7F")
case("escape_unicode_four_digits_surrogate", r"\uD800")
case("escape_unicode_braced", r"\u{7F}")
case("escape_unicode_braced_max", r"\u{10FFFF}")
case("escape_unicode_braced_out_of_range", r"\u{110000}")
case("escape_unicode_braced_surrogate", r"\u{DC00}")
case("escape_unicode_braced_empty", r"\u{}")
case("escape_long_unicode_eight_digits", r"\U0000007F")
case("escape_long_unicode_eight_digits_max", r"\U0010FFFF")
case("escape_long_unicode_eight_digits_out_of_range", r"\U00110000")
case("escape_long_unicode_eight_digits_surrogate", r"\U0000D800")
case("escape_long_unicode_too_few", r"\U7F")
case("escape_long_unicode_braced", r"\U{7F}")
case("escape_long_unicode_braced_out_of_range", r"\U{110000}")
case("escape_long_unicode_braced_empty", r"\U{}")
case("escape_trailing_backslash", "\\")
case("escape_trailing_backslash_after_literal", "a\\")
case("escape_non_ascii", "\\\u00e9")
case("escape_space", "\\ ")
case("escape_tab_character", "\\\t")
case("escape_newline_character", "\\\n")
punctuation_names = {
    "!": "exclamation", '"': "quote", "#": "hash", "$": "dollar", "%": "percent", "&": "ampersand",
    "'": "apostrophe", "(": "open_paren", ")": "close_paren", "*": "star", "+": "plus", ",": "comma",
    "-": "hyphen", ".": "dot", "/": "slash", ":": "colon", ";": "semicolon", "<": "less_than",
    "=": "equals", ">": "greater_than", "?": "question", "@": "at", "[": "open_bracket",
    "\\": "backslash", "]": "close_bracket", "^": "caret", "_": "underscore", "`": "backtick",
    "{": "open_brace", "|": "pipe", "}": "close_brace", "~": "tilde",
}
for c in sorted(punctuation_names):
    case(f"escape_punctuation_{punctuation_names[c]}", "\\" + c)
for c in "0123456789":
    case(f"escape_digit_{c}", "\\" + c)
for c in "abcdefghijklmnopqrstuvwxyz":
    case(f"escape_lower_{c}", "\\" + c)
for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
    case(f"escape_upper_{c}", "\\" + c)

section("Assertions")
for name, pattern in [("caret", "^"), ("dollar", "$"), ("begin_text", r"\A"), ("end_text", r"\z"),
                      ("word_boundary", r"\b"), ("not_word_boundary", r"\B"),
                      ("word_start", r"\b{start}"), ("word_end", r"\b{end}"),
                      ("word_start_half", r"\b{start-half}"), ("word_end_half", r"\b{end-half}"),
                      ("word_start_angle", r"\<"), ("word_end_angle", r"\>")]:
    case(f"assertion_{name}", pattern)
    case(f"assertion_{name}_ascii", f"(?-u:{pattern})")
case("assertion_word_boundary_unknown_name", r"\b{foo}")
case("assertion_word_boundary_empty_name", r"\b{}")
case("assertion_word_boundary_uppercase_name", r"\b{START}")
case("assertion_word_boundary_spaced_name", r"\b{ start }")
case("assertion_word_boundary_spaced_name_verbose", r"\b{ start }", "x")
case("assertion_word_boundary_unclosed_name", r"\b{start")
case("assertion_word_boundary_open_brace_at_end", r"\b{")
case("assertion_word_boundary_underscore_name", r"\b{start_half}")
case("assertion_word_boundary_count", r"\b{2}")
case("assertion_word_boundary_range", r"\b{2,3}")
case("assertion_not_word_boundary_braced", r"\B{start}")
case("assertion_word_start_angle_repeated", r"\<+")
case("assertion_word_start_repeated", r"\b{start}*")

section("Unsupported and foreign syntax")
case("foreign_backreference_one", r"(a)\1")
case("foreign_octal_zero", r"\0")
case("foreign_octal_three_digits", r"\101")
case("foreign_end_text_before_newline", r"a\Z")
case("foreign_single_byte", r"\C")
case("foreign_quote", r"\Qa.b\E")
case("foreign_quote_end_alone", r"\E")
case("foreign_previous_match_end", r"\Ga")
case("foreign_keep_out", r"a\Kb")
case("foreign_linebreak", r"\R")
case("foreign_grapheme_cluster", r"\X")
case("foreign_named_character", r"\N{LATIN SMALL LETTER A}")
case("foreign_not_newline", r"\N")
case("foreign_horizontal_space", r"\h")
case("foreign_lookahead", "a(?=b)")
case("foreign_negative_lookahead", "a(?!b)")
case("foreign_lookbehind", "(?<=a)b")
case("foreign_negative_lookbehind", "(?<!a)b")
case("foreign_atomic_group", "(?>a)")
case("foreign_possessive_star", "a*+")
case("foreign_possessive_plus", "a++")
case("foreign_possessive_question", "a?+")
case("foreign_possessive_counted", "a{1,2}+")
case("foreign_comment_group", "a(?#comment)b")
case("foreign_named_backreference", "(?P<n>a)(?P=n)")
case("foreign_named_backreference_k", r"(?<n>a)\k<n>")
case("foreign_subroutine_call", "(?P<n>a)(?P>n)")
case("foreign_recursion", "a(?R)?")
case("foreign_branch_reset", "(?|(a)|(b))")
case("foreign_quoted_group_name", "(?'n'a)")
case("foreign_conditional", "(a)?(?(1)b|c)")
case("foreign_posix_equivalence_class", "[[=a=]]")
case("foreign_posix_collating_symbol", "[[.a.]]")
case("foreign_caret_negated_property", r"\p{^Greek}")

section("Character classes")
case("class_single", "[a]")
case("class_union", "[abc]")
case("class_range", "[a-z]")
case("class_negated", "[^a-z]")
case("class_negated_unicode", "[^\u00e9]")
case("class_nested", "[x[^xyz]]")
case("class_nested_deep", "[[[a]]]")
case("class_intersection", "[a-y&&xyz]")
case("class_intersection_nested_negation", "[0-9&&[^4]]")
case("class_difference", "[0-9--4]")
case("class_symmetric_difference", "[a-g~~b-h]")
case("class_escaped_brackets", r"[\[\]]")
case("class_empty_intersection", "[a&&b]")
case("class_precedence_range_binds_tightest", "[a-cd]")
case("class_precedence_union_over_intersection", "[ab&&bc]")
case("class_precedence_left_to_right", r"[\pL--\p{Greek}&&\p{Uppercase}]")
case("class_precedence_negation_last", "[^a-z&&b]")
case("class_leading_close_bracket", "[]a]")
case("class_negated_leading_close_bracket", "[^]a]")
case("class_only_close_bracket", "[]]")
case("class_empty", "[]")
case("class_negated_empty", "[^]")
case("class_reversed_range", "[z-a]")
case("class_single_point_range", "[a-a]")
case("class_trailing_hyphen", "[a-]")
case("class_leading_hyphen", "[-a]")
case("class_lone_hyphen", "[-]")
case("class_range_to_perl_class", r"[a-\d]")
case("class_range_from_perl_class", r"[\d-z]")
case("class_range_escaped_endpoints", r"[\x00-\x{10FFFF}]")
case("class_range_hex_to_max", r"[a-\x{10FFFF}]")
case("class_escaped_hyphen", r"[a\-z]")
case("class_escaped_caret", r"[\^a]")
case("class_caret_not_first", "[a^]")
case("class_dot_literal", "[.]")
case("class_dollar_literal", "[$]")
case("class_intersection_empty_right", "[a&&]")
case("class_intersection_empty_left", "[&&a]")
case("class_triple_ampersand", "[a&&&b]")
case("class_triple_hyphen", "[a---b]")
case("class_triple_tilde", "[a~~~b]")
case("class_single_ampersand", "[a&b]")
case("class_single_tilde", "[a~b]")
case("class_unclosed", "[a")
case("class_unclosed_open_only", "[")
case("class_unclosed_nested", "[[a]")
case("class_extra_close_bracket", "[a]]")
case("class_unclosed_negated", "[^")
case("class_unclosed_verbose_negated", "(?x)[ ^ a")
case("class_unclosed_inner_negated", "[a[^b")
case("class_unclosed_negated_nested", "[^[a]")
for name in ["alnum", "alpha", "ascii", "blank", "cntrl", "digit", "graph", "lower", "print", "punct",
             "space", "upper", "word", "xdigit"]:
    case(f"class_posix_{name}", f"[[:{name}:]]")
    case(f"class_posix_{name}_negated", f"[[:^{name}:]]")
case("class_posix_unknown", "[[:foo:]]")
case("class_posix_uppercase_name", "[[:ALPHA:]]")
case("class_posix_outside_brackets", "[:alpha:]")
case("class_posix_unclosed", "[[:alpha:]")
case("class_posix_with_other_items", "[a[:digit:]z]")
case("class_posix_double_negation", "[^[:^alpha:]]")
case("class_perl_inside", r"[\d\s\w]")
case("class_perl_negated_inside", r"[\D\S\W]")
case("class_unicode_inside", r"[\pL]")
case("class_unicode_braced_inside", r"[\p{Greek}\d]")
case("class_unicode_negated_inside", r"[\P{L}]")
case("class_negated_unicode_inside", r"[^\pL]")
case("class_word_boundary_inside", r"[\b]")
case("class_begin_text_inside", r"[\A]")
case("class_end_text_inside", r"[\z]")
case("class_word_start_angle_inside", r"[\<]")
case("class_hex_inside", r"[\x41\u{42}\U00000043]")
case("class_escaped_letter_inside", r"[\y]")
case("class_case_insensitive_range", "[a-z]", "i")
case("class_case_insensitive_ascii_range", "(?i-u:[a-z])")
case("class_ascii_negated", "(?-u:[^a])")
case("class_ascii_positive", "(?-u:[a])")
case("class_ascii_negated_posix", "(?-u:[[:^alpha:]])")
case("class_group_syntax_is_literal", "[^(?-u:a)]")

section("Flags")
for flag in "imsRUux":
    case(f"flag_set_{flag}", f"(?{flag})a")
    case(f"flag_clear_{flag}", f"(?-{flag})a")
    case(f"flag_scoped_{flag}", f"(?{flag}:a)b")
    case(f"flag_scoped_clear_{flag}", f"(?-{flag}:a)b")
case("case_insensitive_titlecase", "(?i)\u01c5")
case("flag_set_and_clear_same", "(?i-i)a")
case("flag_repeated", "(?ii)a")
case("flag_repeated_negated", "(?-ii)a")
case("flag_repeated_negation", "(?i--m)a")
case("flag_dangling_negation", "(?i-)a")
case("flag_negation_only", "(?-)a")
case("flag_empty_group", "(?)a")
case("flag_empty_scoped_group", "(?:)a")
case("flag_scoped_empty_body", "(?i:)a")
case("flag_repeated_groups", "(?i)(?i)a")
case("flag_toggle_sequence", "(?i)a(?-i)b(?i)c")
case("flag_all_set", "(?imsRUux)a")
case("flag_all_cleared", "(?-imsRUux)a")
case("flag_unknown", "(?z)a")
case("flag_unknown_n", "(?n)a")
case("flag_unknown_j", "(?J)a")
case("flag_unknown_uppercase_i", "(?I)a")
case("flag_unclosed", "(?i")
case("flag_unclosed_scoped", "(?i:a")
case("flag_crlf", "(?R)")
case("flag_crlf_multi_line", "(?mR)^a$")
case("flag_scope_ends_with_group", "((?i)a)b")
case("flag_verbose_space_inside", "(?x)(?i s)a")
case("flag_builder_options_all", "a", "imsRUx")
case("flag_builder_options_all_ascii", "a", "imsRUx,-u")
case("flag_builder_unicode_disabled", "a", "-u")
case("flag_inline_reenables_unicode", r"(?u)\pL", "-u")
case("flag_builder_unicode_disabled_dot", ".", "-u")
case("flag_builder_unicode_disabled_word", r"\w", "-u")
case("flag_builder_unicode_disabled_non_word", r"\W", "-u")

section("Groups")
case("group_numbered", "(a)")
case("group_numbered_nested", "((a)(b))")
case("group_named_p", "(?P<name>a)")
case("group_named_angle", "(?<name>a)")
case("group_non_capturing", "(?:a)")
case("group_named_digits_after_start", "(?P<a1>x)")
case("group_named_leading_digit", "(?P<1a>x)")
case("group_named_dot", "(?P<a.b>x)")
case("group_named_leading_dot", "(?P<.a>x)")
case("group_named_underscore", "(?P<_a>x)")
case("group_named_only_underscore", "(?P<_>x)")
case("group_named_brackets", "(?P<a[0]>x)")
case("group_named_close_bracket", "(?P<a]>x)")
case("group_named_leading_open_bracket", "(?P<[a>x)")
case("group_named_non_ascii_letter", "(?P<\u00e9>x)")
case("group_named_non_ascii_letter_angle", "(?<\u03b4>x)")
case("group_named_non_ascii_digit", "(?P<a\u0663>x)")
case("group_named_leading_non_ascii_digit", "(?P<\u0663>x)")
case("group_named_letter_number", "(?P<a\u2163>x)")
case("group_named_combining_mark", "(?P<e\u0301>x)")
case("group_named_hyphen", "(?P<a-b>x)")
case("group_named_space", "(?P<a b>x)")
case("group_named_space_verbose", "(?P<a b>x)", "x")
case("group_named_duplicate", "(?P<n>a)(?P<n>b)")
case("group_named_duplicate_mixed_syntax", "(?P<n>a)(?<n>b)")
case("group_named_duplicate_in_alternation", "(?P<n>a)|(?P<n>b)")
case("group_named_empty", "(?P<>a)")
case("group_named_empty_angle", "(?<>a)")
case("group_named_unclosed_name", "(?P<n")
case("group_named_unclosed_body", "(?P<n>a")
case("group_named_missing_close_angle", "(?P<na)")
case("group_named_p_without_angle", "(?Pn>a)")
case("group_named_mixed_with_numbered", "(a)(?P<x>b)(c)")
case("group_named_nested", "(?P<a>(?P<b>x))")
case("group_empty", "()")
case("group_unclosed", "(a")
case("group_unclosed_nested", "((a)")
case("group_stray_close", "a)")
case("group_stray_close_only", ")")
case("group_extra_close", "(a))")
case("group_open_only", "(")
case("group_question_at_end", "(?")
case("group_zero_repetition_keeps_slot", "(a){0}(a)")

section("Repetition")
for name, pattern in [("star", "a*"), ("plus", "a+"), ("question", "a?"), ("star_lazy", "a*?"),
                      ("plus_lazy", "a+?"), ("question_lazy", "a??"), ("exact", "a{2}"),
                      ("at_least", "a{2,}"), ("range", "a{2,3}"), ("exact_lazy", "a{2}?"),
                      ("at_least_lazy", "a{2,}?"), ("range_lazy", "a{2,3}?"), ("zero", "a{0}"),
                      ("zero_to_zero", "a{0,0}")]:
    case(f"repeat_{name}", pattern)
case("repeat_missing_minimum", "a{,3}")
case("repeat_reversed_bounds", "a{2,1}")
case("repeat_bare_open_brace", "{")
case("repeat_bare_open_brace_after_literal", "a{")
case("repeat_unclosed_count", "a{1")
case("repeat_unclosed_after_comma", "a{1,")
case("repeat_unclosed_range", "a{1,2")
case("repeat_non_decimal_count", "a{x}")
case("repeat_empty_braces", "a{}")
case("repeat_spaces_in_braces", "a{ 2 , 3 }")
case("repeat_spaces_in_braces_verbose", "a{ 2 , 3 }", "x")
case("repeat_at_pattern_start", "{2}")
case("repeat_star_at_pattern_start", "*")
case("repeat_plus_at_pattern_start", "+a")
case("repeat_question_alone", "?")
case("repeat_after_alternation", "a|*")
case("repeat_inside_empty_group", "(*)")
case("repeat_after_open_group", "(+a)")
case("repeat_after_flags", "(?i)*")
case("repeat_empty_group", "(){2}")
case("repeat_empty_non_capturing_group", "(?:){3}")
case("repeat_caret", "^*")
case("repeat_dollar", "$?")
case("repeat_word_boundary", r"\b+")
case("repeat_begin_text_counted", r"\A{2}")
case("repeat_stacked_star", "a**")
case("repeat_stacked_plus_star", "a+*")
case("repeat_stacked_lazy_then_star", "a*?*")
case("repeat_triple_question", "a???")
case("repeat_thousand", "a{1000}")
case("repeat_thousand_one", "a{1001}")
case("repeat_u32_max", "a{4294967295}")
case("repeat_u32_overflow", "a{4294967296}")
case("repeat_size_limit_accepted", r"\w{100}")
case("repeat_size_limit_exceeded", r"\w{1000}{1000}")

section("Unicode disabled with the string API")
for name, pattern in [("word", r"\w"), ("non_word", r"\W"), ("digit", r"\d"), ("non_digit", r"\D"),
                      ("space", r"\s"), ("non_space", r"\S"), ("hex_ascii", r"\x7F"),
                      ("hex_non_ascii", r"\xFF"), ("hex_braced_non_ascii", r"\x{E9}"), ("dot", "."),
                      ("word_boundary", r"\b"), ("not_word_boundary", r"\B"),
                      ("literal_non_ascii", "\u00e9"), ("literal_snowman", "\u2603"),
                      ("case_insensitive_literal", "(?i)k"), ("case_insensitive_non_ascii", "(?i)\u00e9")]:
    case(f"ascii_{name}", f"(?-u:{pattern})")
case("ascii_dot_matches_newline", "(?s-u:.)")
case("ascii_crlf_dot", "(?R-u:.)")
case("ascii_property", r"(?-u)\pL")
case("ascii_property_braced", r"(?-u)\p{L}")
case("ascii_property_negated", r"(?-u)\PL")
case("ascii_property_script", r"(?-u:\p{Greek})")
case("ascii_property_in_class", r"(?-u:[\pL])")
case("ascii_property_reenabled", r"(?-u)(?u:\pL)")

section("Unicode properties")
for name, pattern in [
    ("one_letter", r"\pL"), ("one_letter_braced", r"\p{L}"), ("one_letter_negated", r"\PL"),
    ("one_letter_number", r"\pN"), ("one_letter_unknown", r"\pX"), ("one_letter_lowercase", r"\pl"),
    ("general_category_long", r"\p{Letter}"), ("general_category_equals", r"\p{gc=L}"),
    ("general_category_colon", r"\p{gc:L}"), ("general_category_full_name", r"\p{General_Category=Letter}"),
    ("general_category_two_letter", r"\p{Lu}"), ("general_category_long_two_letter", r"\p{Uppercase_Letter}"),
    ("general_category_cased_letter", r"\p{LC}"), ("general_category_cased_letter_ampersand", r"\p{L&}"),
    ("general_category_unassigned", r"\p{Cn}"), ("general_category_surrogate", r"\p{Cs}"),
    ("general_category_unknown_value", r"\p{gc=Foo}"),
    ("script_name", r"\p{Greek}"), ("script_short_name", r"\p{Grek}"), ("script_colon", r"\p{sc:Greek}"),
    ("script_equals_long", r"\p{Script=Greek}"), ("script_extensions", r"\p{scx=Greek}"),
    ("script_extensions_long", r"\p{Script_Extensions=Greek}"), ("script_not_equal", r"\p{sc!=Greek}"),
    ("script_not_equal_negated", r"\P{sc!=Greek}"), ("script_negated", r"\P{Greek}"),
    ("script_common", r"\p{Common}"), ("script_common_short", r"\p{Zyyy}"), ("script_inherited", r"\p{Inherited}"),
    ("script_inherited_short", r"\p{Zinh}"), ("script_inherited_alias", r"\p{Qaai}"), ("script_unknown_value", r"\p{Unknown}"),
    ("script_unknown_value_short", r"\p{Zzzz}"), ("script_han", r"\p{Han}"), ("script_latin", r"\p{Latin}"),
    ("script_no_such_value", r"\p{sc=Foo}"),
    ("loose_spaces", r"\p{ letter }"), ("loose_inner_spaces", r"\p{l e t t e r}"),
    ("loose_mixed_case", r"\p{LeTtEr}"), ("loose_uppercase", r"\p{GREEK}"), ("loose_underscores", r"\p{let_ter}"),
    ("loose_hyphen", r"\p{let-ter}"), ("loose_spaced_equals", r"\p{Script = Greek}"),
    ("loose_spaced_binary", r"\p{White Space}"), ("loose_lowercase_binary", r"\p{whitespace}"),
    ("special_any", r"\p{Any}"), ("special_ascii", r"\p{ASCII}"), ("special_assigned", r"\p{Assigned}"),
    ("special_any_negated", r"\P{Any}"),
    ("age", r"\p{Age:6.0}"), ("age_equals_version", r"\p{Age=V6_0}"), ("age_short", r"\p{age=6.0}"),
    ("age_latest", r"\p{Age=16.0}"), ("age_future", r"\p{Age=17.0}"), ("age_unknown_format", r"\p{Age=6}"),
    ("grapheme_cluster_break", r"\p{gcb=Extend}"), ("grapheme_cluster_break_long", r"\p{Grapheme_Cluster_Break=Extend}"),
    ("word_break", r"\p{wb=Katakana}"), ("word_break_long", r"\p{Word_Break=Katakana}"),
    ("sentence_break", r"\p{sb=ATerm}"), ("sentence_break_long", r"\p{Sentence_Break=ATerm}"),
    ("block_in_prefix", r"\p{InGreek}"), ("block_equals", r"\p{Block=Greek}"), ("block_short", r"\p{blk=Greek}"),
    ("is_prefix_script", r"\p{IsGreek}"), ("is_prefix_category", r"\p{IsL}"),
    ("unknown_name", r"\p{Foo}"), ("unknown_name_value", r"\p{Foo=Bar}"), ("empty_name", r"\p{}"),
    ("unclosed", r"\p{L"), ("missing_name", r"\p"), ("missing_name_negated", r"\P"), ("backslash_name", "\\p\\"),
    ("posix_compat_digit", r"\p{digit}"), ("posix_compat_alpha", r"\p{alpha}"), ("posix_compat_punct", r"\p{punct}"),
    ("posix_compat_word", r"\p{word}"), ("posix_compat_alnum", r"\p{alnum}"), ("posix_compat_xdigit", r"\p{xdigit}"),
    ("posix_compat_space", r"\p{space}"), ("posix_compat_blank", r"\p{blank}"), ("posix_compat_print", r"\p{print}"),
    ("posix_compat_graph", r"\p{graph}"), ("posix_compat_cntrl", r"\p{cntrl}"),
    ("case_insensitive_category", r"(?i)\p{Lu}"),
]:
    case(f"property_{name}", pattern)
for name in ["Alphabetic", "White_Space", "Uppercase", "Lowercase", "Emoji", "Emoji_Presentation",
             "Emoji_Modifier", "Emoji_Modifier_Base", "Emoji_Component", "Extended_Pictographic", "Ideographic",
             "Join_Control", "Hex_Digit", "Noncharacter_Code_Point", "Math", "Dash", "Diacritic", "Cased",
             "ID_Start", "XID_Continue",
             # Outside the set above.
             "ASCII_Hex_Digit", "Bidi_Mirrored", "Bidi_Control", "Case_Ignorable", "Changes_When_Casefolded",
             "Default_Ignorable_Code_Point", "Deprecated", "Grapheme_Base", "Grapheme_Extend", "ID_Continue",
             "IDS_Binary_Operator", "ID_Compat_Math_Start", "Logical_Order_Exception", "Other_Alphabetic",
             "Pattern_Syntax", "Pattern_White_Space", "Prepended_Concatenation_Mark", "Quotation_Mark", "Radical",
             "Regional_Indicator", "Sentence_Terminal", "Soft_Dotted", "Terminal_Punctuation", "Unified_Ideograph",
             "Variation_Selector", "XID_Start", "Full_Composition_Exclusion", "Basic_Emoji", "RGI_Emoji",
             "Indic_Conjunct_Break", "Modifier_Combining_Mark"]:
    case(f"property_binary_{name.lower()}", "\\p{" + name + "}")
for name, alias in [("alpha", "Alpha"), ("white_space", "WSpace"), ("upper", "Upper"), ("lower", "Lower"),
                    ("emoji_presentation", "EPres"), ("extended_pictographic", "ExtPict"), ("ideographic", "Ideo"),
                    ("join_control", "Join_C"), ("hex_digit", "Hex"), ("ascii_hex_digit", "AHex"),
                    ("noncharacter", "NChar"), ("xid_continue", "XIDC"), ("id_start", "IDS")]:
    case(f"property_binary_alias_{name}", "\\p{" + alias + "}")
case("property_binary_with_value_true", r"\p{Alphabetic=Yes}")
case("property_binary_with_value_false", r"\p{Alphabetic=No}")

section("Verbose mode")
case("verbose_inline_space", "(?x)a b")
case("verbose_option_space", "a b", "x")
case("verbose_class_space", "(?x)[a b]")
case("verbose_class_space_before_caret", "(?x)[ ^a]")
case("verbose_class_space_in_range", "(?x)[a - z]")
case("verbose_escaped_space", r"(?x)a\ b")
case("verbose_comment_then_newline", "(?x)a#comment\nb")
case("verbose_comment_ends_pattern", "(?x)a # trailing")
case("verbose_comment_ends_pattern_option", "a # trailing", "x")
case("verbose_comment_hides_close_paren", "(?x)(a # )")
case("verbose_escaped_hash", r"(?x)a\#b")
case("verbose_hash_in_class", "(?x)[#]a")
case("verbose_space_in_group_prefix", "(?x)( ?:a)")
case("verbose_space_in_flags", "(?x)(? i)a")
case("verbose_space_between_atom_and_quantifier", "(?x)a +")
case("verbose_space_in_property", r"(?x)\p{ L }")
case("verbose_space_after_property_prefix", r"(?x)\p {L}")
case("verbose_scoped_disable", "(?x)a(?-x) b")
case("verbose_scoped_group", "(?x: a b ) c")
case("verbose_tab_and_newline", "(?x)a\t\n\rb")

section("Empty and alternation edge cases")
case("empty_pattern", "")
case("empty_group", "()")
case("empty_alternation_in_group", "(|)")
case("empty_middle_alternative", "a||b")
case("empty_leading_alternative", "|a")
case("empty_trailing_alternative", "a|")
case("empty_alternation_only", "|")
case("empty_non_capturing_alternation", "(?:|)")

section("Octal option")
for name, pattern in [("zero", r"\0"), ("seven", r"\7"), ("two_digits", r"\10"), ("three_digits", r"\141"),
                      ("max_byte", r"\377"), ("above_byte", r"\400"), ("max", r"\777"),
                      ("four_digits", r"\1411"), ("four_digits_above_byte", r"\4000"),
                      ("eight", r"\8"), ("nine", r"\9"), ("zero_then_eight", r"\08"), ("one_then_nine", r"\19"),
                      ("eight_after_literal", r"a\8"), ("class", r"[\141-\142]"), ("class_eight", r"[\8]"),
                      ("class_nine_range", r"[\9-a]"), ("class_range_reversed", r"[\142-\141]"),
                      ("class_then_digit", r"[\1411]"), ("repeated", r"\141{2}"),
                      ("after_group", r"(a)\1"), ("ascii", r"(?-u:\377)"), ("verbose_space", r"(?x)\1 41")]:
    case(f"octal_{name}", pattern, "o")
case("octal_disabled_eight", r"\8")
case("octal_disabled_class_zero", r"[\0]")

section("Nest limit")
case("nest_limit_groups_at_limit", "(" * 250 + "a" + ")" * 250)
case("nest_limit_groups_past_limit", "(" * 251 + "a" + ")" * 251)
case("nest_limit_non_capturing_at_limit", "(?:" * 250 + "a" + ")" * 250)
case("nest_limit_non_capturing_past_limit", "(?:" * 251 + "a" + ")" * 251)
case("nest_limit_classes_at_limit", "[" * 250 + "a" + "]" * 250)
case("nest_limit_classes_past_limit", "[" * 251 + "a" + "]" * 251)
case("nest_limit_repetitions_at_limit", "a" + "*" * 250)
case("nest_limit_repetitions_past_limit", "a" + "*" * 251)
# Each construct nested to a depth the crate counts one level per group,
# bracketed class, repetition, alternation, or concatenation.
nest_constructs = [
    ("groups", lambda depth: "(" * depth + "a" + ")" * depth),
    ("classes", lambda depth: "[" * depth + "a" + "]" * depth),
    ("repetitions", lambda depth: "a" + "*" * depth),
    ("alternations", lambda depth: "(?:" * (depth - 1) + "a|b" + ")" * (depth - 1)),
    ("concatenations", lambda depth: "(?:" * (depth - 1) + "ab" + ")" * (depth - 1)),
]
case("nest_limit_0_literal", "a", "n0")
case("nest_limit_0_empty", "", "n0")
for limit in [0, 1, 2, 251]:
    for name, nest in nest_constructs:
        if limit > 0:
            case(f"nest_limit_{limit}_{name}_at_limit", nest(limit), f"n{limit}")
        case(f"nest_limit_{limit}_{name}_past_limit", nest(limit + 1), f"n{limit}")
case("nest_limit_1_class_intersection", "[a&&b]", "n1")
case("nest_limit_2_class_intersection", "[a&&b]", "n2")
case("nest_limit_1_with_other_options", "(a)", "o,-u,n1")
