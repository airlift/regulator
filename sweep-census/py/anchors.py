# Method anchors per component: phase 1 (tag 2.1) with the Nov 16 revision on main (S8, Q2, Q4B) and L5X. Each value: (run, build, size_extra)
#   run:   regex over "Class.method" whose entry in the timed operation (op phase) means the component ran
#   build: regex whose entry at setup/compile means the component was built for the row (None: same as run)
#   size:  extra regex of methods that belong to the component for its code size (besides run and build)
# Whole-file components list their files in FILES instead of (or in addition to) size regexes.
A = {}
def a(cid, run, build=None, size=None):
    A[cid] = (run, build, size)

# Route selection (compile-time; census via build)
a('R1', r'CompiledPattern\.(selectRoutes|buildWithProgram)$', r'CompiledPattern\.(selectRoutes|buildWithProgram)$',
  r'CompiledPattern\.(analyzeParsed|build|requiredProgramBudget|budgetForForwardMemory|tryReserveForwardDfaMemory|reserveRequiredBytes|tryReserveBesideForwardDfas|leastRequiredBudget|withRequiredTrinoBudget|compactRouteRegexp|compactRouteMemory|mayCheckRequiredLiteralOnLongWindows|checksRequiredLiteralOnLongWindows|routeLiterals)$|CompiledPattern\$Analysis\..*|CompiledPattern\$Shape\..*')
a('R2', r'CompiledPattern\.(matchLiteralPlan|containsBooleanPlanLiteral|equalsFinalLineBooleanPlanLiteral|endsWithFinalLineBooleanPlanLiteral|findOnLiteralKernel|matchesOnLiteralKernel|lookingAtOnLiteralKernel|countOnLiteralKernel)$',
  r'CompiledPattern\.(selectBooleanPlans|selectFindPlan|selectLookingAtPlan|selectCompletePlan)$', r'CompiledPattern\$BooleanPlans\..*|CompiledPattern\$BooleanPlanKind\..*|ExpressionAnalysis.*')
a('R3', r'(Re2|JavaRegexp|RustRegexp)\$[A-Za-z]+(Re2|JavaRegexp|RustRegexp)\.(find|count|matches|lookingAt|matchInternal)$|TrinoCompiledPattern\$\w+\.(find|count|matches|lookingAt|matchInternal)$',
  r'(Re2|JavaRegexp|RustRegexp)\$[A-Za-z]+(Re2|JavaRegexp|RustRegexp)\.<init>$|TrinoCompiledPattern\$\w+\.<init>$', r'(Re2|JavaRegexp|RustRegexp|TrinoCompiledPattern)\$\w+(Re2|JavaRegexp|RustRegexp|CompiledPattern)\..*')
a('R4', r'TrinoRegexp\$(BeginLine|CharacterClass|DotStarLiteral|ExactLiteral|FixedWidthByte|LiteralAlternation|LiteralSet|OrderedLiteral)TrinoRegexp\.(?!<init>).*',
  r'TrinoRegexp\$(BeginLine|CharacterClass|DotStarLiteral|ExactLiteral|FixedWidthByte|LiteralAlternation|LiteralSet|OrderedLiteral)TrinoRegexp\.<init>$', r'TrinoRegexp\$\w+TrinoRegexp\..*|TrinoRegexp\.compile$')
a('R5', r'RegexpMatcher\.(findSpanSearch|findWithStartAnchoredScanPlan|findAnchoredSubmatch|findOnProgramFromSpanSearch|createLongSearchCandidateStartCursor|settleQuitCandidate)$', r'RegexpMatcher\.<init>$', r'RegexpMatcher\..*')
# Core engines
a('E1', r'Dfa\$DfaInstance\.(beginSearch|computeTransitionLocked)$', r'Prog\.getCachedDfa$', r'Dfa\$DfaInstance\.(computeTransition|workQueueToCachedState|addToQueue|runWorkQueueOnByte|runWorkQueueOnEmptyString|allocateCachedState|resetCache|startCache|analyzeStart|startFlags|stateReferences|stateData|beginSearch|endSearch)$|Dfa\.(search|searchForward|searchForwardContinuation|searchForwardScanAcceleration|searchMany|searchManyFull|countMatches|searchForwardForCount|finalizeResult|getTransition|setTransition|getStateReference|setStateReference)$|Dfa\$(StateCache|StateKey|WorkQueue|CacheTables|StateData|StateSaver)\..*')
a('E2', r'Dfa\.(searchForwardAbsolutePointers|searchBackwardAbsolutePointers)$|Dfa\$DfaInstance\.(allocateAbsolutePointerTransitions|setAbsolutePointerTransition|backfillAbsolutePointerTransitions)$', None,
  r'Dfa\$DfaInstance\.(requestAbsolutePointerTransitions|shouldUseAbsolutePointersForShortSearch|hasAbsolutePointerTransitions|absolutePointerTransition\w*|sizeAbsolutePointerTransitionGrowth|allocateAbsolutePointerTransitionGrowth|supersede\w*|releaseUnreachableAbsolutePointerTables|absolutePointerTableBytes|allocateAbsolutePointerTable|releaseAbsolutePointerTransitionsForSlowReset|computedNoTransition|supersededAbsolutePointerMemory\w*)$|Dfa\$(AbsolutePointer\w*|AbsoluteAddressSpace|SupersededAbsolutePointerReference)\..*')
a('E3', r'Dfa\.searchForwardPairs(AfterMiss|AfterMatch|FromState)?$', r'Dfa\$DfaInstance\.(rebuildPairedTransitions|requestPairedTransitions)$',
  r'Dfa\$DfaInstance\.(hasPairedTransitions|recordPairedSearch|nextShortPairedSearchCount|storeCountShortPairedSearchCount|releaseRejectedPairedTransitions|buildPairedStateRefArrays|buildPairedRowOffsets|selectPairedRows|selectCachedStartRows|maximumPairedRows|pairedTransitionMemory|pairedRowLength|pairedRowBytes|pairedOuterMemory|pairedRowMemory|clearPairedTransitions|estimatedPairedTransitionMemory)$|Dfa\.(pairedStateReferences|pairedRowOffset|getPairedSlot|decodePairedTransition|encodePairedTransition|countHandoffKeepsPairedRows)$')
a('E3F', r'Dfa\.searchForwardPairsFlat(AfterMiss|AfterMatch|FromState)?$', None, r'Dfa\.selectFlatPairedRows$')
a('E4', r'Dfa\.(searchForwardCountSelfLoopScan|scanSelfLoopTransitions|searchForwardSampledSelfLoops|scanMatchingSelfLoopTransitions|findSelfLoopExit|stepMatchingSelfLoopTransitions|scanMatchingSelfLoopTransitionsBackward|skipReverseSelfLoop|stepSelfLoopRearmBytes)$', None,
  r'Dfa\.(recordCountSelfLoopScanProductivity|skipsShortStayRun|shouldSampleSelfLoopTransitions|isMatchingSelfLoop)$|Dfa\$DfaInstance\.(selfLoopExitByteSet|countSelfLoopExitByteSet|selfLoopStayRanges|acceptedSelfLoopExitByteSet)$|VectorByteSetScanner\.(findOutsideRanges|isOutsideRanges)$')
a('E5', r'Dfa\$DfaInstance\.(recordCountBail|recordBooleanSearchSlowReset|recordManyMatchSearchSlowReset)$|RegexpMatcher\.markSkipsForwardDfa$', None,
  r'Dfa\$DfaInstance\.(slowResetAllowance|isSlowReset|recordCountCompleted|startBooleanSearch|judgeBooleanSearch|recordBooleanSearchStreakEnded|startManyMatchSearch|judgeManyMatchSearch|recordManyMatchSearchStreakEnded|countResetsTooOften|countHandsResetsToLock)$|Dfa\.(searchJudgingSlowResets|searchManyFullJudgingSlowResets|skipsBooleanSearchDfa)$|CompiledPattern\.(dfaQuitStreak|skipsForwardDfa)$|RegexpMatcher\.(dfaQuitStreak|skipsForwardDfa)$')
a('E6', r'Dfa\.(searchCandidateBoundary|searchLiteralCandidateBoundary|searchLiteralCandidateBoundaryReportingQuits|searchAnchoredCandidate)$', r'Dfa\.(createCandidateStartCursor|createLiteralCandidateStartCursor)$',
  r'Dfa\$CandidateStartCursor\..*|CompiledPattern\.(createCandidateStartCursor|excludesCandidateStartCursor)$|Dfa\.(isQuitCandidate|quitCandidateStart)$|CompiledPattern\.matchQuitCandidateInto$')
a('E6G', r'Dfa\.searchGroupZeroForward$', r'Dfa\.createGroupZeroForwardCursor$', r'Dfa\$GroupZeroForwardCursor\..*|CompiledPattern\.(createGroupZeroForwardCursor|canUseGroupZeroForwardCursor)$')
a('E7', r'Dfa\.(searchBackward|searchReverseCandidate|searchReverseCandidateExists|searchReverseThenAnchoredWithStop)$', r'CompiledPattern\.compileReverse$', r'CompiledPattern\.(reverseProg|reverseProgramMemory|reverseProgramFanout)$|Dfa\.(searchAnchoredWithStop|searchAnchoredEarliestWithStop|anchoredStopResult|anchoredStopPosition)$')
a('E8', r'OnePass\.(search|searchBoolean|searchGroupZero|searchGroupZeroEnd|searchExtendedCaptures)$', r'Prog\.computeOnePass$', r'OnePass.*|Prog\.(prepareOnePass|onePass\w*|releaseOnePass|restoreOnePass|isOnePass\w*|supportsOnePassCaptureSlots|addOnePassQueue)$')
a('E9', r'BitState\$BitStateImpl\.search$', None, r'BitState.*|CompiledPattern\.canUseDirectBitStateCapture$')
a('E10', r'Nfa\$(NfaImpl|NoSubmatchNfaImpl)\.search$', None, r'Nfa.*|Sparse(Set|IntArray)\..*')
a('E11', r'PositionNfa\.(earliestEnd\w*|nextStart)$|CompiledPattern\.searchOnPositionNfa$', r'PositionNfa\$Builder\.build$|PositionNfa\.build$', r'PositionNfa.*|Prog\.(configurePositionNfa|positionNfa)$')
a('E12', r'TaggedAlternationProgram\.search$|Nfa\.searchTagged$', r'TaggedAlternationProgram\.compile$', r'TaggedAlternationProgram\..*|CompiledPattern\.materializeTaggedAlternation$')
a('E13', r'(Re2Set|FilteredRe2)\.(match|firstMatch|allMatches)\w*$', None, r'(Re2Set|FilteredRe2|Prefilter|PrefilterTree)(\$\w+)?\..*')
# Start acceleration
a('S1', r'Prog\.(prefixAccelFusedVector|prefixAccelProbe|prefixAccelNoFoldCaseFrontAndBackSwar|continueFrontAndBack|finishWithKmp|indexOfSWAR|indexOfEitherSWAR)$|VectorPrefixScanner\.(find|findPair|findPairMasked|findScalar)$', r'Prog\.configurePrefixAccelBytes$',
  r'Prog\.(configurePrefixAccel|configurePrefixAccelShared|prefixAccelStrategy|pairOffset|adjacentPairOffset|prefixShiftsMemory|prefixMatchesAt|prefixMatchingLength|canUseSingleBytePrefixAccelFastPath|usesVectorForSingleBytePrefix|prefixAccelSingleByteNoFoldCase|prefixScanMemory)$|VectorPrefixScanner\.pairDifference$')
a('S2', r'Prog\.(prefixAccelFoldCase|prefixAccelFoldCaseFrontAndBackSWAR|findFoldCaseSwar|indexOfFoldCase|prefixAccelFoldCaseBehindLead)$|VectorPrefixScanner\.(findFoldCase\w*|foldCasePrefixMatchesAt)$', None,
  r'Prog\.(usesFoldCasePrefixCandidateScan|usesVectorForFoldCasePrefix|prefixAccelFoldCase\w*|usesDenseFoldCaseScan|foldedPairScan|foldCase\w*)$|Prog\$FoldedPairScan\..*|ByteFrequencies\$FoldedProbePair\..*')
a('S3', r'VectorPrefixScanner\.(findLiteralSet|findLiteralSetScalar|literalSetMatchesAt)$', r'Prog\.buildPrefixLiteralSet$', r'Prog\.(deferPrefixLiteralSet|decidePrefixLiteralSet|defersPrefixLiteralSet|chargePrefixLiteralSet|pathLiteral|prefixLiteralSetMemory|prefixAccelLiteralSet)$')
a('S4', r'Dfa\.searchFoldedRunCandidateBoundary$', r'Prog\.configureFoldCasePrefixRun$', r'Prog\$FoldedStartRun\..*|Regexp\$FoldedRun\w*\..*|CompiledPattern\.rarerFoldedStartRun$|Prog\.(prefixRunStartsEveryMatch|prefixRunAddsPrefixScan|prefixRunHidesOneBitStartLiteralFilter)$')
a('S5', r'Prog\.(skipToStartCandidate|findLiteral)$', r'Prog\.buildTransparentStartLiteral$', r'Prog\.(configureTransparentStartLiteral|transparentStartLiteral\w*|canSkipToStartCandidate|updateStartCandidateSkip)$|CompiledPattern\.searchesTransparentStartLiteral$')
a('S6', r'CompiledPattern\.matchesRequiredPrefix$|Prog\.textMatchesRequiredPrefix$', r'Prog\.configureRequiredPrefix$', r'CompiledPattern\.requiredPrefixBytes$|Regexp\.requiredPrefix\w*$|Prog\.(hasRequiredPrefix|getRequiredPrefix|isRequiredPrefixFoldCase)$')
a('S7', r'Dfa\.(findByteSetCandidate|findByteSet|findByteCandidate)$|Dfa\$DfaInstance\.findStartByteCandidate$|VectorByteSetScanner\.(find|findHighBitOr|findRange|findNibbleTable|findOne|findTwo|findTwoSve|findThree|findThreeSve|findHighBit|findHighBitOr\w+)$',
  r'Dfa\.(buildStartByteScanSet|buildSmallByteSet)$', r'Dfa\.(selectByteScanSet|selectEveryMatchCountByteScanSet|countStartSampleCandidates|isByteCandidateScanProductive|countByteSetRoute|canUseStartByteAcceleration)$|Dfa\$DfaInstance\$StartByteTables\..*|Dfa\$CountByteScanSampling\..*|Prog\.(buildStartByteCandidates|walkStartBytes|boundaryBytes|addStartByteRange)$|VectorByteSetScanner\.(buildNibbleTables|shouldSampleCandidateDensity|vectorLoopBound|shortScanCount)$')
# S8 (Nov 16): findFixedDistanceByteCandidate is called only from tests. The fixed-distance scan reads
# DfaInstance.fixedDistanceSmallByteSet() at its three accept sites (Dfa.java ~847, ~1858, ~2338), each right
# before searchForwardScanAcceleration with the fixed-distance candidates, and nowhere else. Admission:
# DfaInstance.isDenserThanStartFilter, reached only when the lazily built candidates are usable, in any phase.
a('S8', r'Dfa\$DfaInstance\.fixedDistanceSmallByteSet$', r'Dfa\$DfaInstance\.isDenserThanStartFilter$',
  r'Dfa\$DfaInstance\.(fixedDistance\w*|canFixedDistanceByteAcceleration|isFixedDistanceByteScanProductive)$|Dfa\.(canUseFixedDistanceByteAcceleration|isFixedDistanceByteScanProductive)$|Dfa\$\w+\.isFixedDistanceByteScanProductive$|Prog\.(buildFixedDistanceByteCandidates|buildStartOffsetByteSets|walkByteOffset|countCandidates)$|Prog\$FixedDistanceByteCandidates\..*')
a('S9', r'StartLiteralFilter\.(find|accepts)$|VectorByteSetScanner\.(findLiteralSet|findTwoLiterals\w*|findTwoExactLiterals\w*|findLiteralNibbles\w*|xorTwoLiteralMatches|literalMatches)$', r'StartLiteralFilter\.build$', r'StartLiteralFilter\..*|Dfa\.buildStartLiteralFilter$|Dfa\$DfaInstance\$StartLiteralFilterSelection\..*|Dfa\$StartLiteralFilterChoice\..*|Dfa\$DfaInstance\.(selectStartLiteralFilter|searchesStartLiteralFilter|givesOneBitStartLiteralFilter|startLiteralFilterScannerWidth)$|VectorByteSetScanner\.(shouldFilterRareStartsWithTwoLiterals|literalDifference)$')
a('S10', r'StartClassFilter\.(find|accepts)$|VectorByteSetScanner\.(findClassPair\w*|findClassSequence\w*)$', r'StartClassFilter\.build$', r'StartClassFilter\..*|Dfa\.(buildStartClassFilter|englishProbability)$')
a('S11', r'Dfa\.(searchForwardAnchoredLongPrefixInt|searchForwardPrefixAccelSingleByte)$')
# Early rejection
a('Q1', r'RequiredBytes\.occursIn$', r'RequiredBytes\.analyze$', r'RequiredBytes\.(analyzeLiteral|exactLiteral|startBytes|exactBytes|exactAffix|literalRunes|foldsCase|exactRuneCount|asciiBytes|rarerLiteral|literalLogProbability|analyzeOneOf|oneOfBytes|asciiByte|isExactAscii|union|rarer|probability|broadcast|isExactLiteral|windowLimit|withWindowLimit|containsByte|holdsAny|matchesAt|estimatedRetainedSize|isOneOf|isSingleByte|singleByteValue|elements|ended|joinedLiteral)$|CompiledPattern\.shortWindowLacksRequiredBytes$')
# Q2 (Nov 16): containsOneOf left out; only RequiredBytes.occursIn (Q1) calls it. analyze.py moves Q2's indexIn
# entries to Q4B where the static dump marks the long-window search as longWindowLiteral=other.
a('Q2', r'RequiredBytes\.(indexIn|indexOfOneOf)$', None,
  r'RequiredBytes\.(admitsLongWindowSearch|withLongWindowSearch|withoutLongWindowSearch|searchesLongWindows|isLongWindowsOnly|longWindowLiteralForDiagnostics|oneOfByteMask)$|RegexpMatcher\.restOfRegionLacksRequiredLiteral$|CompiledPattern\.(longWindowLacksRequiredLiteral|longWindowRequiredLiteral|nearRequiredLiteralScansInLoop|loopSearchEnd|matchIntoAfterLoopCheck)$')
a('Q3', r'RequiredBytes\.lastIndexIn$|VectorPrefixScanner\.(findLast|findLastScalar)$|Prog\.findLastLiteral$', None,
  r'RequiredBytes\.(sharedSuffix|requiredSuffix|exactSuffix|withLongWindowSearchAndBound|withoutSearchEndBound|boundsSearchEnd|suffixHoldsLiteral|suffixLength|suffixForDiagnostics)$|RequiredBytes\$ExactSuffix\..*|CompiledPattern\.(longWindowSearchEnd|boundsSearchEnd)$|VectorByteSetScanner\.findLast\w*$')
a('Q4', r'RequiredBytes\.handoffIndexIn$|InnerLiteralSearch\.searchFromCheckedHit$|CompiledPattern\.findFromRequiredLiteral$', None,
  r'RequiredBytes\.(literalBesideRoutes|withBesideRouteSearch|admitsInnerLiteralHandoff|withInnerLiteralHandoff|withoutInnerLiteralHandoff|handsOffToInnerLiteralSearch|hasOwnHandoffSearch)$|RequiredBytes\$(BesideRouteComparisons|RouteLiteral)\..*|CompiledPattern\.handsOffRequiredLiteral$')
# Q4B (Nov 16): the beside-route half of Q4, RequiredBytes.indexIn on a long-window search for a literal other
# than the required bytes, which only withBesideRouteSearch builds. Credited in analysis from the static dump.
a('Q4B', r'^$', r'RequiredBytes\.withBesideRouteSearch$', r'RequiredBytes\.(literalBesideRoutes|withBesideRouteSearch)$|RequiredBytes\$(BesideRouteComparisons|RouteLiteral)\..*')
a('Q5', r'^$', r'MatchLength\.analyze$', r'MatchLength.*')
# Literal search
a('L1', r'CompiledPattern\.(findOnExactLiteral|countOnExactLiteral|matchesOnExactLiteral|lookingAtOnExactLiteral|findExactLiteral)$|TrinoRegexp\$ExactLiteralTrinoRegexp\.(?!<init>).*', None, r'(Re2|JavaRegexp|RustRegexp)\$ExactLiteral\w+\..*|TrinoCompiledPattern\$ExactLiteral\w+\..*|TrinoRegexp\.nextExactLiteralSearchStart$')
a('L10', r'CompiledPattern\.(findExactLiteralSpan|findExactLiteralSpanWithPrefixScan|oneShotExactLiteral)$', None, r'CompiledPattern\.(exactLiteralSpanUsesPrefixScan|hasExactLiteralSpan|setOneShotSpanGroups)$')
a('L2', r'LiteralSearchKernel\.(findContains|findLastContains|skipToContainsCandidate|verifyContainsCandidates|findKmp|findLastKmp)$', r'LiteralSearchKernel\.<init>$', r'LiteralSearchKernel\..*')
a('L3', r'LiteralMatchKernel\.(matchesShortLiteralAt|equals|startsWith|endsWith|contains|find|matchesAt)$', None, r'LiteralMatchKernel\..*')
a('L4', r'InnerLiteralSearch\.(search|searchForCount|searchGated|searchGatedFrom|searchUngatedFrom|confirm|confirmCandidate)$', r'InnerLiteralSearch\.(analyze|build)$', r'InnerLiteralSearch(\$\w+)?\..*|CompiledPattern\.(findOnInnerLiteralSearch|findAfterInnerLiteralFallback|runsInnerLiteralSearchFirst|reachesInnerLiteralSearchFirst|countInnerLiteralMatches|searchesInnerLiteral\w*|literalSearchFallsBackNearStart|searchesMultiByteInnerLiteral|innerLiteralPrecedesFixedWidth)$')
a('L4G', r'InnerLiteralSearch\.(passesByteGates|passesDeeperPrefixGates|inByteGate)$', None, r'InnerLiteralSearch\.(gateBytes|byteGate|wordGate|suffixGate|literalEdgeBytes)$|InnerLiteralSearch\$ByteGate\..*')
a('L4F', r'InnerLiteralSearch\.(findFoldedLiteral|findFoldedLiteralVector|foldedRestMatches)$', None, r'InnerLiteralSearch\.(foldedMasks|foldedLiteral|appendFoldedElement|asciiLetterCasePair|otherCase)$|InnerLiteralSearch\$Folded\w*\..*')
a('L4S', r'InnerLiteralSearch\.(findLiteralSetMember|findClassPairMember|findClassPair)$', None, r'InnerLiteralSearch\.(asciiLiteralSet|asciiClassPair|buildLiteralSetFilter|setMemberLength|matchedLength|literalSetFilterForDiagnostics)$|InnerLiteralSearch\$ClassPair\..*')
a('L5', r'WindowLiteralSearch\.(search|searchExists|nextLiteral|findLiteral)$', r'WindowLiteralSearch\.analyze$', r'WindowLiteralSearch(\$\w+)?\..*|CompiledPattern\.(countWindowLiteralMatches|windowLiteralSearchFallsBackNearStart)$|LiteralSearchKernel\.(searchesWindow|countWindowSearch)$')
# L5X: the window literal search stepping aside for a one- or two-literal start literal filter with 64-byte vectors
# (WindowLiteralSearch.keepsOneOrTwoLiteralStartFilter, true only at 64 lanes, as with AVX-512 on r8i). Admission
# only, credited in analysis where that check was entered more often than the search was constructed.
a('L5X', r'^$', r'WindowLiteralSearch\.keepsOneOrTwoLiteralStartFilter$', r'^$')
a('L6', r'DotStarLiteralSpanMatcher\.(findSpan|findSpanWithinLine|count|afterLastLineFeed|findLiteral|indexOfLiteral)$', r'DotStarLiteralSpanMatcher\.analyze$', r'DotStarLiteralSpanMatcher\.(?!\$).*|TrinoRegexp\$DotStarLiteralTrinoRegexp\..*|CompiledPattern\.createDotStarLiteralSpanMatcher$')
a('L6M', r'DotStarLiteralSpanMatcher\$MultipleStops\.(findSpan|findSpanBetweenStops|count|countMatches)$', r'DotStarLiteralSpanMatcher\$MultipleStops\.analyze$', r'DotStarLiteralSpanMatcher\$MultipleStops\..*|ExpressionAnalysis\$DotStops\..*')
a('L6O', r'CompiledPattern\.oneShotDotStarLiteral$')
a('L7', r'OrderedLiteralMatcher\.matches$', r'OrderedLiteralMatcher\.analyze$', r'OrderedLiteralMatcher\..*|CompiledPattern\.(createByteGapOrderedLiteralMatcher|createOrderedLiteralMatcher)$|TrinoRegexp\$OrderedLiteralTrinoRegexp\..*')
a('L8', r'LiteralNeighborMatcher\.find$|CompiledPattern\.findOnLiteralNeighbors$', r'LiteralNeighborMatcher\.analyze$', r'LiteralNeighborMatcher\..*')
a('L9', r'CompiledPattern\.(matchLoweredBooleanProgram|findLoweredCandidateStart|findLoweredGroupZeroBoundary)$', r'CompiledPattern\.compileLoweredBooleanProgram$', r'BooleanRegexpLowering(\$\w+)?\..*|CompiledPattern\.(selectLoweredBooleanPlans|hasLoweredCandidateSearch|hasDirectLoweredGroupZeroSearch|canUseDirectLoweredGroupZeroSearch)$')
# Literal alternation
a('A1', r'LiteralAlternationSpanMatcher(\$ExactMatcher|\$FoldedMatcher)?\.(findSpan|count|findSpanOrDecline|countOrDecline|findSpanInRoute|findSpanAtStop|findSpanInRootTable)$', r'LiteralAlternationSpanMatcher\.analyze$', r'LiteralAlternationSpanMatcher(\$\w+)*\..*|CompiledPattern\.(findOnLiteralAlternation|countOnLiteralAlternation)$')
a('A2', r'LiteralSetSpanMatcher\.(findSpan|count|searchAutomaton|walkTrie|walkTrieSkippingRootBytes)$', r'LiteralSetSpanMatcher\.analyze$', r'LiteralSetSpanMatcher(\$\w+)*\..*|CompiledPattern\.(analyzeLiteralSetWithoutProgram|matchLiteralSet|matchInternalOnLiteralSet)$|TrinoRegexpMatcher\$LiteralSetMatcher\..*')
a('A2S', r'LiteralSetSpanMatcher\.(skipAhead|skipAheadScanningRootBytes)$', None, r'LiteralSetSpanMatcher\$SkipTable\..*|LiteralSetSpanMatcher\.withSkipTable$')
# Fixed width
a('F1', r'FixedWidthByteSpanMatcher\.(count|countOrBail|findSpan|findSpanOrBail|searchCharacters|searchLong|searchLongCharacters|matchesBytes|matchAround)$', r'FixedWidthByteSpanMatcher\.analyze$', r'FixedWidthByteSpanMatcher(\$Position)?\..*|CompiledPattern\.(createFixedWidthByteSpanMatcher|mayHaveFixedWidthByteSpanMatcher|matchFixedWidthByteSpan|findOnFixedWidth|countOnFixedWidth|matchInternalOnFixedWidth|analyzeFixedWidthWithoutProgram|shouldAnalyzeFixedWidthByteSpanForTrino)$')
a('F2', r'FixedWidthByteSpanMatcher\.(searchBytes|searchLongBytes|findCandidate|findCandidateVector|firstScanChoice|resampledScanChoice)$', None, r'FixedWidthByteSpanMatcher\.(rarestCandidate|englishProbability|candidateBytes|rarestAsciiProbability|addLeadBytes|leadByte)$')
a('F2B', r'FixedWidthByteSpanMatcher\.(searchCandidateByte|countCandidateBytes)$', None, r'FixedWidthByteSpanMatcher\.(usesSingleByteVectorScan|countCandidateByteSearch|candidateByteSearchCountForDiagnostics)$')
a('F3', r'FixedWidthByteSpanMatcher\.(searchBlocks|blockMembers|blockStarts|startsFrom)$|VectorBlockMaskScanner\..*', None, r'FixedWidthByteSpanMatcher\.blockClasses$|FixedWidthByteSpanMatcher\$BlockClasses\..*|VectorBlockMaskScanner\..*')
a('F4', r'CompiledPattern\.shortFixedWidthByteMatcher$')
a('F5', r'DisjointSuffixRepeatMatcher\.(count|search)$', r'DisjointSuffixRepeatMatcher\.analyze$', r'DisjointSuffixRepeatMatcher\..*')
a('F6', r'SingleByteMatcher\.(count|find)$', r'SingleByteMatcher\.analyze$', r'SingleByteMatcher(\$\w+)?\..*|CompiledPattern\.(sharedSingleByteMatcher|mayHaveSingleByteMatcher|findOnByteTable|countOnByteTable)$')
a('F7', r'SingleByteRepeatMatcher\.count$|SingleByteRepeatMatcher\$Cursor\.find$', None, r'SingleByteRepeatMatcher(\$\w+)?\..*|CompiledPattern\.createSingleByteRepeatMatcher$')
# Class runs
a('K1', r'BoundedCharacterClassCounter\.(count|countBytes|countUtf8|countDecoded|countRun|countRuns|findSpan|countMembers|contains)$', r'BoundedCharacterClassCounter\.(analyzeCompactRoute|analyzeMatcherRoute|create)$', r'BoundedCharacterClassCounter(\$Repeat)?\..*|CompiledPattern\.(countOnClassCounter|countOnClassRun|findOnClassRun|matchCompactBoundedCharacterClass|createBoundedCharacterClassCounter|boundedCharacterClassCounter|loadBoundedCharacterClassCounter|findCharacterClassSpan|matchInternalOnClassRun)$')
a('K1R', r'BoundedCharacterClassCounter\.(countRunStarts|countRunStartRoute)$')
a('K1V', r'BoundedCharacterClassCounter\.vectorRunEnd$', None, r'BoundedCharacterClassCounter\.byteRunEnd$')
a('K2', r'RetainedCharacterClassCountDfa\.count$', None, r'RetainedCharacterClassCountDfa\..*')
a('K3', r'WordRunMatcher\.(search|searchLongRuns|contains)$', r'WordRunMatcher\.analyze$', r'WordRunMatcher\..*')
a('K4', r'WholeInputCapturePlan\.matches$', None, r'WholeInputCapturePlan(\$\w+)?\..*')
a('K8', r'CompiledPattern\.writeClassRunCaptures$', r'ClassRunAlternationCaptures\.runExpression$', r'ClassRunAlternationCaptures\..*')
# Scan plans
a('P1', r'ScanPlan\.(search|matchEndAnchored|matchPartial|matchUnanchoredAt|searchWholeLiteralCandidates|handOff|searchBeforeHandoff)$', r'ScanPlan\.analyze$', r'ScanPlan(\$\w+)?\..*|CompiledPattern\.(findOnScanPlan|countOnScanPlan|matchStartAnchoredScanPlan|searchScanPlan|finishScanSearch|matchOnScanPlan|matchesOnScanPlan|lookingAtOnScanPlan|matchInternalOnScanPlan|countRouteOnScanPlan|countUnanchoredScanMatches|continueScanSearch|findWithStartAnchoredScanPlan|startAnchoredScanPlan|caseSensitiveScanPlan)$|(Re2|JavaRegexp|RustRegexp)\$ScanPlan\w+\..*')
a('P2', r'ScanPlan\.(searchAsciiFolded|matchAsciiFoldedEndAnchored|matchAsciiFoldedPartial|matchAsciiFoldedUnanchoredAt|nextAsciiFoldedCandidate|findAsciiFoldedByte)$', None, r'ScanPlan\$AsciiFoldedLiteral\..*|ScanPlan\.(usesAsciiFoldedExecutor|hasAsciiFoldedOperation)$')
a('P4', r'ScanPlanRun\.(match|matchMinimum|re2Match\w*|re2Scan|re2Set\w*|scan|findCandidate|findCandidateInTable)$|VectorScanner\.find\w+$', r'ScanPlanRun\.analyze$', r'ScanPlanRun\..*|Utf8MemberTable(\$\w+)?\..*|VectorScanner\..*')
a('G1', r'(ScanKernelSupport|VectorScanKernelSupport)\.(?!<clinit>|<init>).*', r'ScanKernelGenerator\.\w+$', r'ScanKernelGenerator(\$\w+)*\..*|ScanKernelSupport\..*|VectorScanKernelSupport\..*|(Re2|JavaRegexp|RustRegexp|TrinoRegexp)\$CompiledScan\w+\..*|TrinoRegexpMatcher\$CompiledScanMatcher\..*')
a('G2', r'TrinoLikeKernelSupport\.(?!<clinit>|<init>).*', r'TrinoLikeKernelGenerator\.\w+$', r'TrinoLikeKernelGenerator(\$\w+)*\..*|TrinoLikeKernelSupport\..*|TrinoLikePattern\$CompiledTrinoLikePattern\..*')
# Trino LIKE
a('K5', r'TrinoLikePattern\.(matches|matchesComplex)$|TrinoLikePattern\$(ContainsMatcher|OrderedMatcher|LiteralGapPlanMatcher|WildcardMatcher)\..*', r'TrinoLikePattern\.(compile|tryCompileSimpleAscii)$', r'TrinoLikePattern(\$(?!CompiledTrinoLikePattern)\w+)?\..*|TrinoLikeParser(\$\w+)?\..*')
a('K6', r'LiteralGapMatcher\.matches$', None, r'LiteralGapMatcher\..*')
a('K7', r'TrinoLikeWildcardMatcher\.matches$', None, r'TrinoLikeWildcardMatcher(\$\w+)?\..*')
a('T1', r'TrinoReplacementPlan(\$\w+)?\.(writeMatches|writeGroupFreeTemplate|flushMatches|build|buildWritten)$', None, r'TrinoReplacementPlan(\$\w+)?\..*')
# Vector scanners: counted where any search method of the class ran (whole files sized in render21.WHOLE).
a('V1', r'VectorByteSetScanner(\$\w+)?\.(?!<clinit>|<init>|lambda)\w+$', None, r'^$')
a('V2', r'VectorPrefixScanner(\$\w+)?\.(?!<clinit>|<init>|lambda)\w+$', None, r'^$')
a('V3', r'VectorSupport(\$\w+)?\.(?!<clinit>|<init>|lambda|isSupported|species)\w+$', None, r'^$')

# Platform forms: methods only one platform reaches, each with its gate and the method other platforms run in
# its place. check_anchors.py checks each form is credited to the same components as its counterpart (E3 and E3F
# split by design), and SUMMARY.md counts the units that entered each form. Compile-time platform choices with
# no method of their own: S4's folded start run is left out at 64 lanes when every match starts with it (the DFA
# keeps its start bytes; S4 then shows fewer admitted and ran rows), the start literal filter takes two literals
# for rare start bytes only at 64 lanes (S9 rows), the class counter steps runs of 64 bytes rather than 128 at
# 64 lanes (same K1 methods), and L5X marks the window literal search stepping aside at 64 lanes.
X86 = 'x86 (os.arch other than aarch64)'
X86_64B = 'x86 with 64-byte vectors (AVX-512)'
SVE = 'aarch64 with 128-bit SVE'
PLATFORM_FORMS = [(f'Dfa.searchForwardPairs{s}', X86, f'Dfa.searchForwardPairsFlat{s}') for s in ('', 'AfterMiss', 'AfterMatch', 'FromState')] + [
    ('VectorPrefixScanner.findPairMasked', X86_64B, 'VectorPrefixScanner.findPair'),
    ('VectorByteSetScanner.findLiteralNibblesOneLoad', X86_64B + ' and VBMI', 'VectorByteSetScanner.findLiteralNibbles'),
    ('VectorByteSetScanner.findTwoExactLiteralsXor', X86_64B + ', or ' + SVE, 'VectorByteSetScanner.findTwoExactLiterals'),
] + [(f'{c}.{m}Sve', SVE, f'{c}.{m}') for c, m in (
    ('VectorByteSetScanner', 'findTwo'), ('VectorByteSetScanner', 'findThree'), ('VectorByteSetScanner', 'findLastTwo'),
    ('VectorByteSetScanner', 'findLastThree'), ('VectorByteSetScanner', 'findTwoLiterals'), ('VectorByteSetScanner', 'findClassPair'),
    ('VectorByteSetScanner', 'findClassSequencePair'), ('VectorByteSetScanner', 'findClassSequenceTriple'),
    ('VectorPrefixScanner', 'findFoldCase'), ('VectorPrefixScanner', 'findFoldCaseDense'), ('VectorBlockMaskScanner', 'members'),
    ('VectorScanner', 'findRunStop'), ('VectorScanKernelSupport', 'findTwo'), ('VectorScanKernelSupport', 'findThree'),
    ('VectorScanKernelSupport', 'findRunStop'))]
