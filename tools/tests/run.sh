#!/usr/bin/env bash
# Unit tests for the plain-Java logic (no Android needed): compiles and runs each test.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=out/test; rm -rf "$OUT"; mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$OUT" app/src/io/ibtzy/tvlauncher/GamesLogic.java app/src/io/ibtzy/tvlauncher/IconShape.java app/src/io/ibtzy/tvlauncher/ContinueLogic.java app/src/io/ibtzy/tvlauncher/HomeNav.java tools/tests/GamesLogicTest.java tools/tests/IconShapeTest.java tools/tests/ContinueLogicTest.java tools/tests/HomeNavTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" GamesLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" IconShapeTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" ContinueLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" HomeNavTest
