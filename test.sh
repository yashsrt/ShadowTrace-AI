#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out src/ShadowTraceAI.java src/ShadowTraceTests.java
java -cp out ShadowTraceTests
