#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
CHERRY_TEST_REPO="${CHERRY_TEST_REPO:-https://github.com/lmrrcc/cherry-test}"
CHERRY_TEST_VERSION="${CHERRY_TEST_VERSION:-main}"
JAR_NAME="cherry-test-java.jar"
LAUNCHER_NAME="cherry-test-java"
MINIMUM_JAVA=25

usage() {
  cat <<'USAGE'
Install cherry-test-java, the Java 25 edition of Cherry Test.

Usage:
  java/install.sh [OPTIONS]

Options:
  --install-dir DIR   Install destination directory (default: ~/.local/bin)
  -h, --help          Show this help

Environment:
  CHERRY_TEST_REPO        Git URL or local checkout path (default: lmrrcc/cherry-test main)
  CHERRY_TEST_VERSION     Git branch when installing from a remote repo (default: main)
  INSTALL_DIR             Same as --install-dir when the flag is omitted
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir)
      if [[ $# -lt 2 ]]; then
        echo "error: --install-dir requires a directory argument" >&2
        exit 1
      fi
      INSTALL_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v java >/dev/null 2>&1; then
  echo "error: java not found; install a JDK $MINIMUM_JAVA or newer" >&2
  exit 1
fi
java_major=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p')
if [[ -z "$java_major" ]] || (( java_major < MINIMUM_JAVA )); then
  echo "error: cherry-test-java needs JDK $MINIMUM_JAVA or newer, found ${java_major:-unknown}" >&2
  exit 1
fi

if [[ "$CHERRY_TEST_REPO" == /* ]] || [[ "$CHERRY_TEST_REPO" == ./* ]]; then
  source_root="$CHERRY_TEST_REPO"
else
  if ! command -v git >/dev/null 2>&1; then
    echo "error: git not found; install Git or set CHERRY_TEST_REPO to a local checkout" >&2
    exit 1
  fi
  source_root=$(mktemp -d "${TMPDIR:-/tmp}/cherry-test-java-install.XXXXXX")
  trap 'rm -rf "$source_root"' EXIT
  git clone --quiet --depth 1 --branch "$CHERRY_TEST_VERSION" "$CHERRY_TEST_REPO" "$source_root"
fi

"$source_root/java/mvnw" -q -B -f "$source_root/java/pom.xml" -pl cherry-test-cli -am package -DskipTests

install_root=$(dirname "$INSTALL_DIR")
mkdir -p "$install_root/lib" "$install_root/bin"
cp "$source_root/java/cherry-test-cli/target/$JAR_NAME" "$install_root/lib/$JAR_NAME"
printf '#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED -jar "%s" "$@"\n' "$install_root/lib/$JAR_NAME" \
  >"$install_root/bin/$LAUNCHER_NAME"
chmod 755 "$install_root/bin/$LAUNCHER_NAME"

case ":$PATH:" in
  *":$install_root/bin:"*) ;;
  *)
    echo "add $install_root/bin to your PATH, for example:"
    printf '  export PATH="%s:$PATH"\n' "$install_root/bin"
    ;;
esac

echo "installed cherry-test-java to $install_root/bin/$LAUNCHER_NAME"
