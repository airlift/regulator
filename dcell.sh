#!/bin/bash
# On-host, script mode (run-jobs.sh): dcell.sh <out prefix> <bundle> <perfasm|perfnorm>   UAP-2 D-cell fork (untimed
# diagnostic, never a score), the spec's command (AWS-JOBS-UAP-2.md): BenchmarkPatternCollections.matchingSetPatternIds,
# patternCount=16, sourceLength=32768, -f 1, the template JVM options.
# - perfasm: -prof 'perfasm:intelSyntax=true;top=3;saveLog=true' plus saveLogToFile=<out>.perfasm (so each leg's
#   annotated log has its own name; saveLog alone writes a generated name in the cwd). zstd-compressed afterwards.
# - perfnorm: -prof 'perfnorm:events=cycles,instructions,<alias>' where <alias> is runs/alias-event.txt (host-prep:
#   ld_blocks_partial.address_alias if perf knows it, else the name or raw code it found; see that file).
# Adapted from EG-9's diag.sh (copied, read-only source). Writes <out>.json last.
set -u
out=$1; bundle=$2; kind=$3
JAVA=/opt/jdk/bin/java
[[ -f ~/runs/hsdis-ok ]] || { echo "hsdis/perf check failed (runs/hsdis-ok missing); not running"; exit 1; }
mkdir -p "$(dirname "$out")"
deps=$(sed "s|^|$HOME/bundles/|" ~/bundles/deps/cp.txt | tr '\n' ':')
cp="$HOME/bundles/$bundle/test-classes:$HOME/bundles/$bundle/classes:$deps"
jvm="--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch"
case $kind in
  perfasm) prof="perfasm:intelSyntax=true;top=3;saveLog=true;saveLogToFile=$out.perfasm" ;;
  perfnorm) prof="perfnorm:events=cycles,instructions,$(cat ~/runs/alias-event.txt | head -1)" ;;
  *) echo "unknown kind $kind"; exit 1 ;;
esac
echo "bundle $bundle revision $(cut -d' ' -f1 ~/bundles/$bundle/revision.txt) prof $prof"
cd ~/runs
$JAVA -Xms64m -Xmx256m -cp "$cp" org.openjdk.jmh.Main \
  '^io.airlift.regulator.BenchmarkPatternCollections\.matchingSetPatternIds$' \
  -p patternCount=16 -p sourceLength=32768 -f 1 -jvmArgs "$jvm" -prof "$prof" -rf json -rff "$out.json.tmp"
rc=$?
[[ -f $out.perfasm ]] && zstd -q -T0 -9 --rm "$out.perfasm"
ls -l "$out".*
ok=1; [[ $kind == perfasm && ! -f $out.perfasm.zst ]] && ok=0
[[ $rc == 0 && $ok == 1 && -s $out.json.tmp ]] && grep -q primaryMetric "$out.json.tmp" && mv "$out.json.tmp" "$out.json"
