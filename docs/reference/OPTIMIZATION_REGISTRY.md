# RE2 optimization registry

This registry lists C++ RE2 optimizations and their exact trigger conditions
for upstream fidelity audits. O-RE2-016 is Regulator's compile-time form of an
RE2 run-time shortcut; its entry gives the C++ counterpart. Regulator's own
optimizations, and where each RE2 mechanism lives in Regulator, are in the
[optimization catalog](OPTIMIZATION_CATALOG.md).

**Upstream commit**: `972a15cedd008d846f1a39b2e88ce48d7f166cbd`

Impact labels:

- **CRITICAL**: Changes algorithmic scaling or a dominant execution path
- **IMPORTANT**: Materially affects common execution or memory behavior
- **MINOR**: Localized optimization with limited scope

---

## Table of Contents

1. [DFA Optimizations (O-DFA-*)](#dfa-optimizations)
2. [Program Optimizations (O-PROG-*)](#program-optimizations)
3. [Compiler Optimizations (O-COMP-*)](#compiler-optimizations)
4. [OnePass Optimizations (O-1PASS-*)](#onepass-optimizations)
5. [RE2 Engine Selection (O-RE2-*)](#re2-engine-selection)
6. [NFA Optimizations (O-NFA-*)](#nfa-optimizations)
7. [BitState Optimizations (O-BIT-*)](#bitstate-optimizations)

---

## DFA Optimizations

### O-DFA-001: DeadState Sentinel (CRITICAL)

**Location**: `dfa.cc:480`, `693-697`
**Function**: `WorkqToCachedState`, `InlinedSearchLoop`

**Trigger condition**:
```cpp
// WorkqToCachedState (lines 693-697)
if (n == 0 && flag == 0) {
  if (ExtraDebug)
    absl::FPrintF(stderr, " -> DeadState\n");
  return DeadState;
}
```

**Search loop check** (lines 1469-1473):
```cpp
if (ns <= SpecialStateMax) {
  if (ns == DeadState) {
    params->ep = reinterpret_cast<const char*>(lastmatch);
    return matched;
  }
```

**Effect**: When workq has zero instructions and no match flag, returns DeadState sentinel. Search terminates immediately.

**Performance impact**: Major speedup for rejecting non-matching inputs early. Avoids processing O(n) remaining bytes.

---

### O-DFA-002: FullMatchState Sentinel (CRITICAL)

**Location**: `dfa.cc:483`, `638-651`
**Function**: `WorkqToCachedState`, `InlinedSearchLoop`

**Trigger condition**:
```cpp
case kInstAltMatch:
  // This state will continue to a match no matter what
  // the rest of the input is.
  if (kind_ != Prog::kManyMatch &&
      (kind_ != Prog::kFirstMatch ||
       (it == q->begin() && ip->greedy(prog_))) &&
      (kind_ != Prog::kLongestMatch || !sawmark) &&
      (flag & kFlagMatch)) {
    if (ExtraDebug)
      absl::FPrintF(stderr, " -> FullMatchState\n");
    return FullMatchState;
  }
```

**Precise conditions for FullMatchState**:
1. Current instruction is `kInstAltMatch`
2. Match kind is NOT `kManyMatch`
3. For `kFirstMatch`: must be at beginning of workq AND greedy
4. For `kLongestMatch`: must not have seen a Mark
5. State must already be matching (`flag & kFlagMatch`)

**Effect**: Any remaining input matches. Search returns immediately.

**Performance impact**: O(1) completion for patterns like `(?s).*` (matches everything).

---

### O-DFA-003: State Caching / Memoization (IMPORTANT)

**Location**: `dfa.cc:746-800`
**Function**: `CachedState`

**Trigger condition**: Always enabled
```cpp
StateSet::iterator it = state_cache_.find(&state);
if (it != state_cache_.end()) {
  return *it;
}
```

**Effect**: States are cached in hash set. Same input byte from same state yields cached next state.

---

### O-DFA-004: Search Loop Specialization (IMPORTANT)

**Location**: `dfa.cc:1345-1617`
**Function**: `InlinedSearchLoop`, `FastSearchLoop`

**Template parameters**:
```cpp
template <bool can_prefix_accel,
          bool want_earliest_match,
          bool run_forward>
inline bool DFA::InlinedSearchLoop(SearchParams* params);
```

**8 specialized variants**: SearchFFF through SearchTTT

**Effect**: Compile-time specialization eliminates per-byte conditional branches.

---

### O-DFA-005: Prefix Acceleration (CRITICAL)

**Location**: `dfa.cc:1391-1400`, `1711-1715`
**Function**: `InlinedSearchLoop`, `AnalyzeSearch`

**Trigger condition in search loop**:
```cpp
if (can_prefix_accel && s == start) {
  p = BytePtr(prog_->PrefixAccel(p, ep - p));
  if (p == NULL) {
    p = ep;
    break;
  }
}
```

**Enable condition in AnalyzeSearch**:
```cpp
if (prog_->can_prefix_accel() &&
    !params->anchored &&
    params->start > SpecialStateMax &&
    params->start->flag_ >> kFlagNeedShift == 0)
  params->can_prefix_accel = true;
```

**Effect**: In start state, skip ahead using memchr/SIMD instead of byte-by-byte DFA.

**Performance impact**: Skips non-matching regions without running the complete
DFA transition loop at every byte.

---

### O-DFA-006: Byte Class Compression (ByteMap) (IMPORTANT)

**Location**: `dfa.cc:314-318`
**Function**: `ByteMap`

**Effect**: Reduces state transition table from 256 entries to `bytemap_range()` (typically 4-20).

**Performance impact**: Reduces transition storage and improves cache locality.

---

### O-DFA-007: Early Match Termination (IMPORTANT)

**Location**: `dfa.cc:1381-1385`, `1498-1501`
**Function**: `InlinedSearchLoop`

**Trigger condition**:
```cpp
if (want_earliest_match) {
  params->ep = reinterpret_cast<const char*>(lastmatch);
  return true;
}
```

**Effect**: In first-match mode, returns immediately upon finding any match.

---

### O-DFA-008: Lock-Free State Transition Reading (IMPORTANT)

**Location**: `dfa.cc:1426-1428`
**Function**: `InlinedSearchLoop`

**Hot path (no lock)**:
```cpp
State* ns = s->next_[bytemap[c]].load(std::memory_order_acquire);
if (ns == NULL) {
  ns = RunStateOnByteUnlocked(s, c);
```

**Effect**: Atomic loads without locks for cached transitions.

---

### O-DFA-009: Hint-Based Instruction Skipping (MINOR)

**Location**: `dfa.cc:981-992`
**Function**: `RunWorkqOnByte`

**Trigger condition**:
```cpp
if (ip->hint() != 0) {
  i += ip->hint() - 1;
}
```

**Effect**: After ByteRange matches, skip to next list using hint.

---

### O-DFA-010: FirstMatch WorkQ Short-Circuit (IMPORTANT)

**Location**: `dfa.cc:1000-1003`
**Function**: `RunWorkqOnByte`

**Trigger condition**:
```cpp
case kInstMatch:
  if (prog_->anchor_end() && c != kByteEndText &&
      kind_ != Prog::kManyMatch)
    break;
  *ismatch = true;
  if (kind_ == Prog::kFirstMatch) {
    return;  // Stop processing
  }
  break;
```

---

### O-DFA-011: List Head Compression (MINOR)

**Location**: `dfa.cc:653-663`
**Function**: `WorkqToCachedState`

**Effect**: Only stores list heads in state. Reduces state size.

---

### O-DFA-012: Empty-Width Flag Pruning (MINOR)

**Location**: `dfa.cc:669-674`
**Function**: `WorkqToCachedState`

**Trigger condition**:
```cpp
if (needflags == 0)
  flag &= kFlagMatch;
```

**Effect**: Strips unnecessary flags when no empty-width instructions need them.

---

### O-DFA-013: NFA Fallback Detection (IMPORTANT)

**Location**: `dfa.cc:1439-1444`
**Function**: `InlinedSearchLoop`

**Trigger condition**:
```cpp
if (dfa_should_bail_when_slow && resetp != NULL &&
    static_cast<size_t>(p - resetp) < 10*state_cache_.size() &&
    kind_ != Prog::kManyMatch) {
  params->failed = true;
  return false;
}
```

**Effect**: If DFA processes <10 bytes per state, fall back to NFA.

---

### O-DFA-014: Cached Start State (IMPORTANT)

**Location**: `dfa.cc:1726-1748`
**Function**: `AnalyzeSearchHelper`

**8 cached start states** for different contexts (begin-text, begin-line, after-word, etc.)

---

### O-DFA-015: State Sorting for LongestMatch (MINOR)

**Location**: `dfa.cc:699-723`
**Function**: `WorkqToCachedState`

**Trigger condition**: `kind_ == Prog::kLongestMatch` or `kind_ == Prog::kManyMatch`

**Effect**: Canonicalizes instruction order for better cache hits.

---

### O-DFA-016: FullMatchState Self-Loop (MINOR)

**Location**: `dfa.cc:1027-1033`
**Function**: `RunStateOnByte`

**Effect**: FullMatchState transitions to itself on any byte.

---

### O-DFA-017: Per-Kind DFA Budgets and Minimum State Count (IMPORTANT)

**Location**: `dfa.cc:420-467`, `1801-1828`
**Function**: `DFA::DFA()`, `Prog::GetDFA()`

**Budget split** (`GetDFA`): a forward first-match or longest-match DFA gets
`dfa_mem_ / 2`; a many-match DFA, and the longest-match DFA of a reversed
program, get all of `dfa_mem_`.

**Trigger condition** (`DFA::DFA`, lines 456-462):
```cpp
int nnext = prog_->bytemap_range() + 1;  // + 1 for kByteEndText slot
int64_t one_state = sizeof(State) + nnext*sizeof(std::atomic<State*>) +
                    (prog_->list_count()+nmark)*sizeof(int);
if (state_budget_ < 20*one_state) {
  init_failed_ = true;
  return;
}
```

**Effect**: After the DFA, work queues, and stack are charged to the budget,
construction fails unless the rest holds 20 states.

---

### O-DFA-018: State Kept Across Cache Reset (MINOR)

**Location**: `dfa.cc:1202-1276`, `1445-1459`
**Function**: `DFA::StateSaver`, `InlinedSearchLoop`

**Effect**: Before `ResetCache`, a `StateSaver` copies a state's instruction
list and flags out of the cache (special states are kept as pointers);
`Restore()` re-creates it in the emptied cache, so the search continues from
its current and start states instead of starting over.

---

### O-DFA-019: Many-Match DFA (IMPORTANT)

**Location**: `dfa.cc:643`, `719-734`, `1103-1106`, `1439-1441`, `1490-1497`
**Function**: `WorkqToCachedState`, `RunStateOnByte`, `InlinedSearchLoop`

**Trigger condition**: `kind_ == Prog::kManyMatch`

**Effect**: A matching state stores the match IDs of every pattern that matched
after a `MatchSep` marker, and the search loop inserts them into the caller's
set. Many-match never returns `FullMatchState` and never bails to the NFA when
the cache thrashes, since `RE2::Set` has no fallback.

---

## Program Optimizations

### O-PROG-001: NOP Elimination (MINOR)

**Location**: `prog.cc:230-257`
**Function**: `Prog::Optimize()`

**Trigger condition**:
```cpp
while (j != 0 && (jp=inst(j))->opcode() == kInstNop) {
  j = jp->out();
}
```

**Effect**: Rewires instruction out-pointers to skip NOP chains.

---

### O-PROG-002: AltMatch Insertion (CRITICAL)

**Location**: `prog.cc:259-291`
**Function**: `Prog::Optimize()`

**Trigger condition**:
```cpp
if (ip->opcode() == kInstAlt) {
  Inst* j = inst(ip->out());
  Inst* k = inst(ip->out1());
  if (j->opcode() == kInstByteRange && j->out() == id &&
      j->lo() == 0x00 && j->hi() == 0xFF &&
      IsMatch(this, k)) {
    ip->set_opcode(kInstAltMatch);
    continue;
  }
  // ... reverse case
}
```

**Effect**: Converts `Alt` to `AltMatch` when pattern is `[00-FF] loop + Match`.

**Performance impact**: Enables FullMatchState and early match termination for `.*` patterns.

---

### O-PROG-003: ByteMap Compression (IMPORTANT)

**Location**: `prog.cc:452-526`
**Function**: `Prog::ComputeByteMap()`

**Effect**: Groups 256 bytes into equivalence classes (typically 4-20).

---

### O-PROG-004: Instruction Flattening (IMPORTANT)

**Location**: `prog.cc:563-658`
**Function**: `Prog::Flatten()`

**Effect**: Transforms graph to flat list, eliminates Alt instructions.

---

### O-PROG-005: Skip Hints (ComputeHints) (MINOR)

**Location**: `prog.cc:858-933`
**Function**: `Prog::ComputeHints()`

**Effect**: Computes skip hints for ByteRange instructions.

---

### O-PROG-006: Shift DFA for Literal Prefix (IMPORTANT)

**Location**: `prog.cc:941-1018`
**Function**: `BuildShiftDFA()`

**Trigger condition**:
```cpp
if (prefix_foldcase_) {
  prefix_size_ = std::min(prefix_size_, kShiftDFAFinal);  // max 9 bytes
  prefix_dfa_ = BuildShiftDFA(prefix.substr(0, prefix_size_));
}
```

**Effect**: Builds packed DFA for case-insensitive prefix (up to 9 bytes).

---

### O-PROG-007: Front-and-Back Prefix Acceleration (IMPORTANT)

**Location**: `prog.cc:1136-1179`
**Function**: `Prog::PrefixAccel_FrontAndBack()`

**Trigger condition**: Case-sensitive prefix >= 2 bytes
**Effect**: Uses AVX2 SIMD when available.

---

### O-PROG-008: BitState Eligibility (IMPORTANT)

**Location**: `prog.h:357`, `prog.cc:644-657`
**Function**: `Prog::CanBitState()`

**Trigger condition**:
```cpp
bool CanBitState() { return list_heads_.data() != NULL; }
// Populated when size_ <= 512
```

**Runtime text limit**: `256 Kibit / list_count - 1`

---

### O-PROG-009: OnePass Eligibility (IMPORTANT)

**Location**: `onepass.cc:383-621`
**Function**: `Prog::IsOnePass()`

**Conditions**:
1. `start() != 0`
2. `maxnodes < 65000`
3. Memory fits in `dfa_mem_ / 4`
4. No ambiguity (each byte leads to unique state)
5. Max 5 capturing groups

---

### O-PROG-010: Next Set Bit in a 256-Bit Set (MINOR)

**Location**: `bitmap256.cc:13-41`, `bitmap256.h:52-81`
**Function**: `Bitmap256::FindNextSetBit()`, `Bitmap256::FindLSBSet()`

**Effect**: Finds the next set bit at or after `c` by masking the bits below `c`
in its 64-bit word and counting trailing zeros, then testing the following
words in turn. Byte-class construction (`ByteMapBuilder::Merge`, `Build`) and
`ComputeHints` use it to step between split points.

---

## Compiler Optimizations

### O-COMP-001: RuneCache - Suffix Fragment Caching (MINOR)

**Location**: `compile.cc:448-501`
**Function**: `CachedRuneByteSuffix()`

**Effect**: Caches common UTF-8 suffix fragments (e.g., `[80-BF]` continuation bytes).

---

### O-COMP-002: ASCII Case-Folding (IMPORTANT)

**Location**: `compile.cc:928-953`
**Function**: `PostVisit()` for `kRegexpCharClass`

**Trigger condition**: `cc->FoldsASCII()`

**Effect**: Discards A-Z ranges when a-z exists, sets foldcase flag. Reduces 3 insts/letter to 1.

---

### O-COMP-003: 80-10FFFF Special Case (MINOR)

**Location**: `compile.cc:655-703`
**Function**: `AddRuneRangeUTF8()`

**Trigger condition**:
```cpp
if (lo == 0x80 && hi == 0x10ffff) {
  Add_80_10ffff();
  return;
}
```

---

### O-COMP-004: Nop Elision in Concatenation (MINOR)

**Location**: `compile.cc:282-304`
**Function**: `Cat()`

---

### O-COMP-005: Anchor Detection and Removal (IMPORTANT)

**Location**: `compile.cc:986-1078`
**Function**: `IsAnchorStart()`, `IsAnchorEnd()`

**Effect**: Detects `\A` and `\z`, records as flags, removes from AST.

---

### O-COMP-006-016: Additional Compiler Optimizations

- **O-COMP-006**: Suffix Trie Building (UTF-8)
- **O-COMP-007**: UTF-8 Caching Strategy
- **O-COMP-008**: Nullable Star Handling
- **O-COMP-009**: Post-compilation NOP Elimination
- **O-COMP-010**: AltMatch Generation (same as O-PROG-002)
- **O-COMP-011**: ByteMap Computation
- **O-COMP-012**: Instruction Hints
- **O-COMP-013**: Prefix Acceleration Configuration
- **O-COMP-014**: Flattening
- **O-COMP-015**: DotStar for Unanchored Search
- **O-COMP-016**: ANCHOR_BOTH End-of-Text

---

### O-COMP-017: Lazy Reverse Program with Swapped Assertions (IMPORTANT)

**Location**: `compile.cc:296-300`, `963-973`, `1115-1161`, `re2.cc:287-303`
**Function**: `Compiler::Cat()`, `Compiler::PostVisit()`, `Compiler::Compile()`, `RE2::ReverseProg()`

**Trigger condition** (`PostVisit`, lines 963-973):
```cpp
case kRegexpBeginLine:
  return EmptyWidth(reversed_ ? kEmptyEndLine : kEmptyBeginLine);
case kRegexpEndLine:
  return EmptyWidth(reversed_ ? kEmptyBeginLine : kEmptyEndLine);
case kRegexpBeginText:
  return EmptyWidth(reversed_ ? kEmptyEndText : kEmptyBeginText);
case kRegexpEndText:
  return EmptyWidth(reversed_ ? kEmptyBeginText : kEmptyEndText);
```

**Effect**: A reversed compile reverses every concatenation, swaps begin and
end assertions, and swaps the recorded start and end anchors. `RE2` compiles
the reverse program only on the first `ReverseProg()` call, once, with
`max_mem() / 3`; a failed compile returns `NULL`, and matching falls back to
BitState when eligible, otherwise to the NFA.

---

### O-COMP-018: Unchanged Nodes Kept During Simplification (MINOR)

**Location**: `simplify.cc:202-219`, `242`, `270`, `459-465`, `493`
**Function**: `ChildArgsChanged()`, `CoalesceWalker::PostVisit()`, `SimplifyWalker::PreVisit()`, `SimplifyWalker::PostVisit()`

**Trigger condition**:
```cpp
static bool ChildArgsChanged(Regexp* re, Regexp** child_args) {
  for (int i = 0; i < re->nsub(); i++) {
    Regexp* sub = re->sub()[i];
    Regexp* newsub = child_args[i];
    if (newsub != sub)
      return true;
  }
  ...
  return false;
}
```

**Effect**: At each `ChildArgsChanged` call site, when no child changed, the
walker releases the child results and returns the original node with
`Incref()` instead of allocating a copy. `SimplifyWalker::PreVisit` returns a
node already marked simple without visiting its children.

---

## OnePass Optimizations

### O-1PASS-001: Bit-Packed Action Encoding (CRITICAL)

**Location**: `onepass.cc:148-182`

**Constants**:
```cpp
static const int    kIndexShift   = 16;
static const int    kEmptyShift   = 6;
static const uint32_t kMatchWins  = 1 << kEmptyShift;
static const uint32_t kImpossible = kEmptyWordBoundary | kEmptyNonWordBoundary;
```

**Effect**: Packs next-state, flags, and captures into single uint32_t.

---

### O-1PASS-002: Memory Budget Check (MINOR)

**Location**: `onepass.cc:391-398`

**Trigger condition**:
```cpp
int maxnodes = 2 + inst_count(kInstByteRange);
int statesize = sizeof(OneState) + bytemap_range()*sizeof(uint32_t);
if (maxnodes >= 65000 || dfa_mem_ / 4 / statesize < maxnodes)
  return false;
```

---

### O-1PASS-003 through O-1PASS-020: Additional OnePass Optimizations

- **O-1PASS-003**: Lazy Node Allocation
- **O-1PASS-004**: Work Queue Duplicate Detection
- **O-1PASS-005**: ByteRange Conflict Detection
- **O-1PASS-006**: Multiple Match Detection
- **O-1PASS-007**: Bytemap Skip Optimization
- **O-1PASS-008**: Anchor Validation
- **O-1PASS-009**: Fast Empty-Flag Check
- **O-1PASS-010**: FullMatch Skip-Match
- **O-1PASS-011**: Impossible Match Skip
- **O-1PASS-012**: Match-Wins Priority Skip
- **O-1PASS-013**: First-Match Early Termination
- **O-1PASS-014**: Conditional Capture Application
- **O-1PASS-015**: Impossible State Initialization
- **O-1PASS-016**: Case-Folding Handling
- **O-1PASS-017**: Manual Stack (avoid recursion)
- **O-1PASS-018**: Goto-Loop Continuation
- **O-1PASS-019**: Satisfy() Fast Path
- **O-1PASS-020**: Cap[0]/Cap[1] Skip

---

## RE2 Engine Selection

### O-RE2-001: Options Defaults (IMPORTANT)

**Location**: `re2.h:678-692`, `re2.cc:58-72`

**Defaults**:
- `encoding_`: `EncodingUTF8`
- `longest_match_`: `false` (first-match)
- `max_mem_`: `8 << 20` (8MB)

---

### O-RE2-002: Memory Budget Split (IMPORTANT)

**Location**: `re2.cc:261-270`, `287-302`

**Split**: 2/3 forward, 1/3 reverse
```cpp
prog_ = suffix_regexp_->CompileToProg(options_.max_mem()*2/3);
re->rprog_ = re->suffix_regexp_->CompileToReverseProg(re->options_.max_mem() / 3);
```

---

### O-RE2-003: Required Prefix Extraction (CRITICAL)

**Location**: `re2.cc:251-259`, `regexp.cc:696-731`

**Trigger condition**: Pattern starts with `^` + literal
```cpp
if (entire_regexp_->RequiredPrefix(&prefix_, &foldcase, &suffix)) {
  prefix_foldcase_ = foldcase;
  suffix_regexp_ = suffix;
}
```

---

### O-RE2-004: Prefix Acceleration in DFA (CRITICAL)

**Location**: `prog.h:246-270`, `dfa.cc:1391-1400`

**Three strategies**:
```cpp
if (prefix_foldcase_) {
  return PrefixAccel_ShiftDFA(data, size);
} else if (prefix_size_ != 1) {
  return PrefixAccel_FrontAndBack(data, size);
} else {
  return memchr(data, prefix_front_, size);
}
```

---

### O-RE2-005: One-Pass Engine Selection (IMPORTANT)

**Location**: `re2.cc:283`, `734`, `838-842`

**Trigger condition**:
```cpp
bool can_one_pass = is_one_pass_ && ncap <= Prog::kMaxOnePassCapture;  // <= 5

// Skip DFA for small texts:
if (can_one_pass && text.size() <= 4096 &&
    (ncap > 1 || text.size() <= 16)) {
  skipped_test = true;
}
```

---

### O-RE2-006: BitState Engine Selection (IMPORTANT)

**Location**: `re2.cc:735-736`, `843-847`, `891-895`

**Trigger condition**:
```cpp
bool can_bit_state = prog_->CanBitState();
size_t bit_state_text_max_size = prog_->bit_state_text_max_size();

if (can_bit_state && text.size() <= bit_state_text_max_size && ncap > 1) {
  skipped_test = true;
}
```

---

### O-RE2-007 through O-RE2-015: Additional Engine Selection

- **O-RE2-007**: nsubmatch Optimization (matchp=NULL when nsubmatch==0)
- **O-RE2-008**: Explicit Anchor Detection
- **O-RE2-009**: Anchor-End Special Case (reverse DFA only)
- **O-RE2-010**: DFA Result Sufficiency Check
- **O-RE2-011**: DFA Fallback on Memory Exhaustion
- **O-RE2-012**: FullMatchState Optimization
- **O-RE2-013**: DFA Specialization via Templates
- **O-RE2-014**: want_earliest_match Early Termination
- **O-RE2-015**: Bytemap Compression

---

### O-RE2-016: Match-Every-Byte Detection (IMPORTANT)

**Location**: `Compiler.java`, `CompiledPattern.java`
**Function**: `matchesAnyByteString()`, `matchOnOrdinaryEngine()`

**Trigger condition** (Compiler.java):
```java
private static boolean matchesAnyByteString(Regexp regexp)
{
    if (regexp.op() != RegexpOp.STAR) {
        return false;
    }
    Regexp operand = regexp.child(0);
    return operand.op() == RegexpOp.ANY_BYTE ||
            (operand.op() == RegexpOp.ANY_CHAR && (operand.parseFlags() & Regexp.LATIN1) != 0);
}
```

**Short-circuit condition** (CompiledPattern.java):
```java
if (anchorMode == Anchor.ANCHOR_BOTH && prog.matchesAnyByteString() && captureCount <= 1) {
    if (mutableGroupOffsets != null && mutableGroupOffsets.length >= 2) {
        mutableGroupOffsets[0] = start - contextStart - strippedPrefixLength;
        mutableGroupOffsets[1] = end - contextStart;
    }
    return true;
}
```

**Effect**: Returns immediately for full matches when the expression accepts
every byte sequence. This includes `\C*` in UTF-8 mode and dot-all `.*` in
Latin-1 mode. UTF-8 dot-all `.*` is excluded because it rejects malformed UTF-8.

**C++ counterpart**: RE2 reaches the same answer at run time. `Prog::Optimize`
marks a `[00-FF]*` loop before `Match` as `kInstAltMatch`
([O-PROG-002](#o-prog-002-altmatch-insertion-critical)). The DFA turns a state
holding it into `FullMatchState` once the state's match flag is set, which
happens after the first byte, or the end-of-text step on empty input, and
returns at once
([O-DFA-002](#o-dfa-002-fullmatchstate-sentinel-critical); `dfa.cc:638-650`,
`1469-1476`). BitState and the NFA jump to the end of the text
([O-NFA-006](#o-nfa-006-altmatch-greedy-shortcut-critical); `bitstate.cc:184`,
`nfa.cc:362`), and OnePass ignores it (`onepass.cc:450`). Regulator decides at
compile time instead, so a full match returns before any engine setup and
reads no input.

---

### O-RE2-017: RE2::Set Many-Match Program (IMPORTANT)

**Location**: `set.cc:24-30`, `64-101`, `103-131`, `137-183`, `compile.cc:859-866`, `1219-1259`
**Function**: `RE2::Set::Set()`, `RE2::Set::Add()`, `RE2::Set::Compile()`, `RE2::Set::Match()`, `Compiler::CompileSet()`

**Match call** (`set.cc:154-155`):
```cpp
bool ret = prog_->SearchDFA(text, text, Prog::kAnchored, Prog::kManyMatch,
                            NULL, &dfa_failed, matches.get());
```

**Effect**: The set forces `never_capture`, appends a `HaveMatch(n)` marker to
each pattern (compiled as `Match(n)`, after `\z` for `ANCHOR_BOTH`), and
compiles the sorted patterns as one alternation, with a `.*` prefix when
unanchored. `CompileSet` runs one probe search and fails if the DFA is out of
memory, since there is no NFA fallback. Each `Match` is one many-match DFA
pass ([O-DFA-019](#o-dfa-019-many-match-dfa-important)); with no result vector
it stops at the earliest match.

---

### O-RE2-018: Prefilter Tree and Filtered Matching (IMPORTANT)

**Location**: `prefilter.cc:581-602`, `646-662`, `699-708`, `prefilter_tree.cc:39-70`, `80-115`, `117-266`, `268-330`, `filtered_re2.cc:70-89`, `98-122`
**Function**: `Prefilter::FromRE2()`, `PrefilterTree::Add()`, `PrefilterTree::Compile()`, `PrefilterTree::RegexpsGivenStrings()`, `PrefilterTree::PropagateMatch()`, `FilteredRE2::FirstMatch()`, `FilteredRE2::AllMatches()`

**Filtered match** (`filtered_re2.cc:104-109`):
```cpp
std::vector<int> regexps;
prefilter_tree_->RegexpsGivenStrings(atoms, &regexps);
for (size_t i = 0; i < regexps.size(); i++)
  if (RE2::PartialMatch(text, *re2_vec_[regexps[i]]))
    return regexps[i];
return -1;
```

**Effect**: Each pattern becomes an AND/OR tree of literal atoms. The tree
drops atoms shorter than `min_atom_len`, shares identical nodes, and returns
the atoms for the caller to find in the text. From the matched atoms, an OR
node triggers on one child and an AND node on every child left after edge
pruning; only patterns whose root triggers, plus patterns with no prefilter,
run `RE2::PartialMatch`.

---

## NFA Optimizations

### O-NFA-001: Thread Freelist Pooling (IMPORTANT)

**Location**: `nfa.cc:160-190`
**Function**: `NFA::AllocThread()`, `NFA::Decref()`

**Effect**: Reuses Thread objects via freelist.

---

### O-NFA-002: Reference-Counted Capture Sharing (MINOR)

**Location**: `nfa.cc:176-180`

---

### O-NFA-003: SparseArray-based Dedup (IMPORTANT)

**Location**: `nfa.cc:227-231`

**Trigger condition**:
```cpp
if (q->has_index(id)) {
  continue;  // Already in queue
}
```

---

### O-NFA-004: Preallocated Stack (MINOR)

**Location**: `nfa.cc:144-148`

---

### O-NFA-005: Longest Match Thread Pruning (MINOR)

**Location**: `nfa.cc:341-347`

**Trigger condition**:
```cpp
if (longest_ && matched_ && match_[0] < t->capture[0]) {
  Decref(t);
  continue;
}
```

---

### O-NFA-006: AltMatch Greedy Shortcut (CRITICAL)

**Location**: `nfa.cc:362-379`

**Trigger condition**:
```cpp
case kInstAltMatch:
  if (i != runq->begin())
    break;
  if (ip->greedy(prog_) || longest_) {
    // Claim match, terminate all threads
    matched_ = true;
    // ... clear runq ...
    return ip->out1();  // or ip->out()
  }
```

---

### O-NFA-007 through O-NFA-011: Additional NFA Optimizations

- **O-NFA-007**: First Match Early Termination
- **O-NFA-008**: Prefix Acceleration (memchr skip)
- **O-NFA-009**: Dead Thread Early Exit
- **O-NFA-010**: Shortcut Path Fast-Forward
- **O-NFA-011**: Hint-based Alternative Skipping

---

## BitState Optimizations

### O-BIT-001: Bitmap Memoization (CRITICAL)

**Location**: `bitstate.cc:95-102`
**Function**: `BitState::ShouldVisit()`

**Trigger condition**:
```cpp
int n = list_id * static_cast<int>(text.size()+1) +
        static_cast<int>(p-text.data());
if (visited[n/kVisitedBits] & (uint64_t{1} << (n & (kVisitedBits-1))))
  return false;
visited[n/kVisitedBits] |= uint64_t{1} << (n & (kVisitedBits-1));
return true;
```

**Effect**: O(1) visited check ensures linear time complexity.

---

### O-BIT-002: Program Size Eligibility (IMPORTANT)

**Location**: `prog.cc:644-652`

**Trigger condition**:
```cpp
if (size_ <= 512) {
  list_heads_ = PODArray<uint16_t>(size_);
  // ...
}
```

---

### O-BIT-003: Text Size Memory Limit (IMPORTANT)

**Location**: `prog.cc:654-657`

```cpp
const size_t kBitStateBitmapMaxSize = 256*1024;  // bits
bit_state_text_max_size_ = kBitStateBitmapMaxSize / list_count_ - 1;
```

---

### O-BIT-004: RLE Stack Compression (MINOR)

**Location**: `bitstate.cc:123-133`

**Trigger condition**:
```cpp
if (id >= 0 && njob_ > 0) {
  Job* top = &job_[njob_-1];
  if (id == top->id &&
      p == top->p + top->rle + 1 &&
      top->rle < std::numeric_limits<int>::max()) {
    ++top->rle;
    return;
  }
}
```

---

### O-BIT-005 through O-BIT-015: Additional BitState Optimizations

- **O-BIT-005**: RLE Stack Decompression
- **O-BIT-006**: AltMatch Greedy/Longest Shortcut
- **O-BIT-007**: First Match Early Return (nsubmatch==0)
- **O-BIT-008**: First Match Mode Early Exit
- **O-BIT-009**: Full Text Consumed Exit
- **O-BIT-010**: Same List ShouldVisit Skip
- **O-BIT-011**: Hint-based Alternative Pushing
- **O-BIT-012**: Prefix Acceleration
- **O-BIT-013**: Shared Visited Bitmap (linear time)
- **O-BIT-014**: Capture Undo via Negative IDs
- **O-BIT-015**: Dynamic Stack Growth

---

## Summary: Critical Optimizations

These optimizations have the highest impact and are most likely to cause performance bugs if missing or incorrect:

| ID | Name | File | Impact |
|----|------|------|--------|
| **O-DFA-002** | FullMatchState | dfa.cc | Constant-time completion |
| **O-DFA-001** | DeadState | dfa.cc | Early termination |
| **O-DFA-005** | Prefix Acceleration | dfa.cc | Skips impossible starts |
| **O-PROG-002** | AltMatch Insertion | prog.cc | Enables FullMatchState |
| **O-NFA-006** | AltMatch Shortcut | nfa.cc | O(1) for `.*` |
| **O-BIT-001** | Bitmap Memoization | bitstate.cc | Linear time guarantee |
| **O-RE2-003** | Prefix Extraction | re2.cc | Enables prefix accel |
| **O-RE2-005** | OnePass Selection | re2.cc | Avoids general NFA execution |
| **O-RE2-016** | Match-every-byte detection | Compiler.java | Constant-time full match for byte-universal patterns |

---

## Audit Checklist

When auditing Java implementation, verify each optimization:

1. **Trigger condition matches exactly** (not just "similar")
2. **Effect is identical** (same code path taken)
3. **Protected benchmarks preserve the expected algorithm and scaling**

Where Regulator deliberately differs, check the difference against its catalog
entry and design decision instead.

Use the correctness and performance gates in
[`MAINTAINING_REGULATOR.md`](../MAINTAINING_REGULATOR.md) for changes to these
paths.
