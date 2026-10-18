#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
CHERRY_TEST_JAR=${CHERRY_TEST_JAR:-../../java/cherry-test-cli/target/cherry-test-java.jar}
CHERRY_TEST_JAR="$(cd "$(dirname "$CHERRY_TEST_JAR")" && pwd)/$(basename "$CHERRY_TEST_JAR")"
test -f "$CHERRY_TEST_JAR"
exec docker run --rm --network none \
  --entrypoint java \
  -v "$PWD:$PWD" -w "$PWD" \
  -v "$CHERRY_TEST_JAR:/opt/cherry-test.jar:ro" \
  mini-discord-tests -jar /opt/cherry-test.jar --root . --no-open "$@"
