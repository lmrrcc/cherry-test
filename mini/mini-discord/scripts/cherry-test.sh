#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
CHERRY_TEST_JAR=${CHERRY_TEST_JAR:-../../java/cherry-test-cli/target/cherry-test-java.jar}
if [ "$(uname -s)" = Darwin ]; then
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true"
  export JAVA_TOOL_OPTIONS
  exec sandbox-exec -f scripts/loopback-only.sb java -jar "$CHERRY_TEST_JAR" --root . --no-open "$@"
fi
exec sh scripts/cherry-test-docker.sh "$@"
