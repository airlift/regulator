#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text)')
./mvnw -B -f tools/teavm/pom.xml -Dregulator.version="$version" clean package
node -e 'require("./tools/teavm/target/javascript/pattern-parsing.js").main([])'
node --input-type=module -e 'import {load} from "./tools/teavm/target/webassembly/wasm-runtime.mjs"; const {exports} = await load("./tools/teavm/target/webassembly/pattern-parsing.wasm"); exports.main([]);'
