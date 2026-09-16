#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$repo_root"

if [[ ! -f target/rebar-classpath.txt ]]; then
    echo "Rebar classpath is missing; run tools/re2-benchmark/rebar/build-regulator.sh" >&2
    exit 1
fi

classpath="target/test-classes:target/classes:$(cat target/rebar-classpath.txt)"
java_options=(--add-modules jdk.incubator.vector)
unset JDK_JAVA_OPTIONS JAVA_TOOL_OPTIONS _JAVA_OPTIONS
case ${REBAR_NATIVE_ACCESS:?REBAR_NATIVE_ACCESS must be enabled or disabled} in
    enabled)
        java_options+=(
            --enable-native-access=ALL-UNNAMED
            --illegal-native-access=deny
        )
        ;;
    disabled) ;;
    *)
        echo "Invalid REBAR_NATIVE_ACCESS: ${REBAR_NATIVE_ACCESS}" >&2
        exit 1
        ;;
esac
if [[ -n ${REBAR_HEAP_SIZE:-} ]]; then
    java_options+=("-Xms${REBAR_HEAP_SIZE}" "-Xmx${REBAR_HEAP_SIZE}")
    case ${REBAR_HEAP_PRETOUCH:-true} in
        true) java_options+=(-XX:+AlwaysPreTouch) ;;
        false) java_options+=(-XX:-AlwaysPreTouch) ;;
        *) echo "REBAR_HEAP_PRETOUCH must be true or false" >&2; exit 1 ;;
    esac
fi
if [[ ${REBAR_PRINT_COMPILATION:-false} == true ]]; then
    java_options+=(-XX:+PrintCompilation)
fi
if [[ ${REBAR_BIT_STATE_ASSEMBLY:-false} == true ]]; then
    java_options+=(
        -XX:+UnlockDiagnosticVMOptions
        '-XX:CompileCommand=print,io.airlift.regulator.BitState$BitStateImpl::trySearch'
    )
fi
if [[ ${REBAR_ONE_PASS_ASSEMBLY:-false} == true ]]; then
    java_options+=(
        -XX:+UnlockDiagnosticVMOptions
        '-XX:CompileCommand=print,io.airlift.regulator.OnePass::search'
        '-XX:CompileCommand=print,io.airlift.regulator.OnePass::searchBoolean'
        '-XX:CompileCommand=print,io.airlift.regulator.OnePass::searchGroupZero'
        '-XX:CompileCommand=print,io.airlift.regulator.OnePass::searchGroupZeroEnd'
        '-XX:CompileCommand=print,io.airlift.regulator.OnePass::searchExtendedCaptures'
        '-XX:CompileCommand=print,io.airlift.regulator.Re2::runSubmatchEngine'
        '-XX:CompileCommand=print,io.airlift.regulator.Re2Matcher::find'
        '-XX:CompileCommand=print,io.airlift.regulator.RebarRunner::grepCapturesResult'
        '-XX:CompileCommand=print,io.airlift.regulator.RebarRunner::lineEnd'
        '-XX:CompileCommand=print,io.airlift.regulator.RebarRunner::contentEnd'
        '-XX:CompileCommand=print,io.airlift.regulator.RebarRunner::countParticipatingGroups'
    )
fi
if [[ ${REBAR_DFA_ASSEMBLY:-false} == true ]]; then
    java_options+=(
        -XX:+UnlockDiagnosticVMOptions
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::search'
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::searchBackward'
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::searchBackwardAbsolutePointers'
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::searchGroupZeroForward'
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::searchForwardForCount'
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::searchForwardScanAcceleration'
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::searchForwardAbsolutePointers'
        '-XX:CompileCommand=print,io.airlift.regulator.Dfa::searchForwardContinuation'
    )
fi
if [[ ${REBAR_NFA_ASSEMBLY:-false} == true ]]; then
    java_options+=(
        -XX:+UnlockDiagnosticVMOptions
        '-XX:CompileCommand=print,io.airlift.regulator.Nfa$NfaImpl::search'
        '-XX:CompileCommand=print,io.airlift.regulator.Nfa$NfaImpl::step'
        '-XX:CompileCommand=print,io.airlift.regulator.Nfa$NfaImpl::addToThreadq'
        '-XX:CompileCommand=print,io.airlift.regulator.Nfa$NfaImpl::copyCapture'
    )
fi
if [[ ${REBAR_PUBLIC_CAPTURE_ASSEMBLY:-false} == true ]]; then
    java_options+=(
        -XX:+UnlockDiagnosticVMOptions
        '-XX:CompileCommand=print,io.airlift.regulator.Re2Matcher::find'
        '-XX:CompileCommand=print,io.airlift.regulator.Re2::matchInternal'
        '-XX:CompileCommand=print,io.airlift.regulator.Re2::runSubmatchEngine'
        '-XX:CompileCommand=print,io.airlift.regulator.RebarRunner::matchResult'
        '-XX:CompileCommand=print,io.airlift.regulator.RebarRunner::countParticipatingGroups'
    )
fi
exec java "${java_options[@]}" \
    -cp "$classpath" \
    io.airlift.regulator.RebarRunner "$@"
