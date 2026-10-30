#!/usr/bin/env bash
# Unit tests for the plain-Java logic (no Android needed): compiles and runs each test.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=out/test; rm -rf "$OUT"; mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$OUT" app/src/io/ibtzy/tvlauncher/GamesLogic.java app/src/io/ibtzy/tvlauncher/IconShape.java app/src/io/ibtzy/tvlauncher/ContinueLogic.java app/src/io/ibtzy/tvlauncher/HomeNav.java app/src/io/ibtzy/tvlauncher/TitleWrap.java app/src/io/ibtzy/tvlauncher/MoviesLogic.java app/src/io/ibtzy/tvlauncher/TabsLogic.java app/src/io/ibtzy/tvlauncher/GamesV1Logic.java app/src/io/ibtzy/tvlauncher/AppsLogic.java tools/tests/GamesLogicTest.java tools/tests/IconShapeTest.java tools/tests/ContinueLogicTest.java tools/tests/HomeNavTest.java tools/tests/TitleWrapTest.java tools/tests/MoviesLogicTest.java tools/tests/TabsLogicTest.java tools/tests/GamesV1LogicTest.java tools/tests/AppsLogicTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" GamesLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" IconShapeTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" ContinueLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" HomeNavTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" TitleWrapTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" MoviesLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" TabsLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" GamesV1LogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" AppsLogicTest
