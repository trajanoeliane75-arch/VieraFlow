#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEST_BUILD="$ROOT/build/tests"
mkdir -p "$TEST_BUILD"
find "$ROOT/tests/stubs" -name '*.java' > "$TEST_BUILD/sources.txt"
for name in HlsStream Navigation StreamProxy DlnaController PlaybackState; do
    printf '%s\n' "$ROOT/app/src/main/java/br/dev/sebasty/vieraflow/$name.java" >> "$TEST_BUILD/sources.txt"
done
printf '%s\n' "$ROOT/tests/IntegrationTest.java" >> "$TEST_BUILD/sources.txt"
printf '%s\n' "$ROOT/tests/TransportTest.java" >> "$TEST_BUILD/sources.txt"
javac -d "$TEST_BUILD" @"$TEST_BUILD/sources.txt"
java -cp "$TEST_BUILD" br.dev.sebasty.vieraflow.IntegrationTest "${1:?Pass the generated test media directory}"
java -cp "$TEST_BUILD" br.dev.sebasty.vieraflow.TransportTest
