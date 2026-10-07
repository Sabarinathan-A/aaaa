#!/bin/sh
# ============================================================================
# build.sh - pure-JDK build/test/run driver for the Fraud Detector project.
#
# This project is built WITHOUT Maven/Gradle because the sandbox runs in
# INTEGRATIONS_ONLY network mode: no package registry is reachable and there is
# no local artifact cache. Everything uses ONLY the JDK standard library and is
# compiled with plain `javac` + packaged with `jar`.
#
# Subcommands:
#   clean   remove build output (out/, out-test/)
#   build   compile src/main/java into out/ and package out/app.jar
#   test    compile src/main + src/test, run every *Test class, fail on error
#   run     start the application (com.frauddetector.App) on :8080
#
# NOTE: This script intentionally NEVER invokes mvn or gradle.
# ============================================================================
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"

OUT=out
OUT_TEST=out-test
MAIN_CLASS=com.frauddetector.App
SRC_MAIN=src/main/java
SRC_TEST=src/test/java

# Copy non-Java resources (the web UI) onto the classpath.
copy_resources() {
    if [ -d src/main/resources ]; then
        cp -R src/main/resources/. "$1"/
    fi
}

clean() {
    echo ">> clean: removing $OUT and $OUT_TEST"
    rm -rf "$OUT" "$OUT_TEST"
}

build() {
    echo ">> build: compiling sources from $SRC_MAIN"
    rm -rf "$OUT"
    mkdir -p "$OUT"
    SOURCES=$(find "$SRC_MAIN" -name '*.java')
    if [ -z "$SOURCES" ]; then
        echo "!! no sources found under $SRC_MAIN" >&2
        exit 1
    fi
    javac -encoding UTF-8 -d "$OUT" $SOURCES
    copy_resources "$OUT"
    echo ">> build: packaging $OUT/app.jar (main-class $MAIN_CLASS)"
    jar --create --file "$OUT/app.jar" --main-class "$MAIN_CLASS" -C "$OUT" .
    echo ">> build: done -> $OUT/app.jar"
}

test() {
    echo ">> test: compiling main + test sources into $OUT_TEST"
    rm -rf "$OUT_TEST"
    mkdir -p "$OUT_TEST"
    SOURCES=$(find "$SRC_MAIN" "$SRC_TEST" -name '*.java')
    if [ -z "$SOURCES" ]; then
        echo "!! no sources found under $SRC_MAIN / $SRC_TEST" >&2
        exit 1
    fi
    javac -encoding UTF-8 -d "$OUT_TEST" $SOURCES
    copy_resources "$OUT_TEST"

    echo ">> test: discovering *Test classes"
    PASS=0
    FAIL=0
    FAILED_CLASSES=""
    # Find compiled test classes named *Test.class (exclude inner classes).
    for CLASSFILE in $(find "$OUT_TEST" -name '*Test.class' ! -name '*$*'); do
        CLASSNAME=$(echo "$CLASSFILE" | sed -e "s#^$OUT_TEST/##" -e 's#/#.#g' -e 's#\.class$##')
        printf ">> test: running %s ... " "$CLASSNAME"
        if java -cp "$OUT_TEST" "$CLASSNAME"; then
            echo "PASS"
            PASS=$((PASS + 1))
        else
            echo "FAIL"
            FAIL=$((FAIL + 1))
            FAILED_CLASSES="$FAILED_CLASSES $CLASSNAME"
        fi
    done

    echo ">> test: summary -> $PASS passed, $FAIL failed"
    if [ "$FAIL" -ne 0 ]; then
        echo "!! failed:$FAILED_CLASSES" >&2
        exit 1
    fi
    if [ "$PASS" -eq 0 ]; then
        echo "!! no test classes were found/run" >&2
        exit 1
    fi
}

run() {
    if [ ! -d "$OUT" ]; then
        build
    fi
    echo ">> run: starting $MAIN_CLASS"
    java -cp "$OUT" "$MAIN_CLASS"
}

CMD="$1"
if [ -z "$CMD" ]; then
    echo "usage: $0 {clean|build|test|run} [more subcommands...]" >&2
    exit 2
fi

for sub in "$@"; do
    case "$sub" in
        clean) clean ;;
        build) build ;;
        test)  test ;;
        run)   run ;;
        *) echo "!! unknown subcommand: $sub" >&2; exit 2 ;;
    esac
done
