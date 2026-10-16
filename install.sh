#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
CARGO_INSTALL_ROOT="$(dirname "$INSTALL_DIR")"
CHERRY_TEST_REPO="${CHERRY_TEST_REPO:-https://github.com/lmrrcc/cherry-test}"
CHERRY_TEST_VERSION="${CHERRY_TEST_VERSION:-main}"
CHERRY_TEST_PATH="${CHERRY_TEST_PATH:-test/cherry-test}"

usage() {
  cat <<'EOF'
Install cherry-test and cargo-cherry-test.

Usage:
  ./install.sh [OPTIONS]

Options:
  --install-dir DIR   Install destination directory (default: ~/.local/bin)
  -h, --help          Show this help

Environment:
  CHERRY_TEST_REPO        Git URL or local checkout path (default: lmrrcc/cherry-test main)
  CHERRY_TEST_VERSION     Git branch when installing from a remote repo (default: main)
  CHERRY_TEST_PATH        Crate path for the cherry-test package in a local checkout (default: test/cherry-test)
  CHERRY_TEST_FORCE=1     Pass --force to cargo install
  INSTALL_DIR         Same as --install-dir when the flag is omitted
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir)
      if [[ $# -lt 2 ]]; then
        echo "error: --install-dir requires a directory argument" >&2
        exit 1
      fi
      INSTALL_DIR="$2"
      CARGO_INSTALL_ROOT="$(dirname "$INSTALL_DIR")"
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

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: cargo not found; install Rust from https://rustup.rs" >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR"

install_binaries() {
  local package="$1"
  local crate_path="$2"
  local args=(--locked --bins --root "$CARGO_INSTALL_ROOT")
  if [[ "${CHERRY_TEST_FORCE:-0}" == "1" ]]; then
    args+=(--force)
  fi
  if [[ "$CHERRY_TEST_REPO" == /* ]] || [[ "$CHERRY_TEST_REPO" == ./* ]]; then
    cargo install --path "$CHERRY_TEST_REPO/$crate_path" "${args[@]}"
  else
    cargo install --git "$CHERRY_TEST_REPO" --branch "$CHERRY_TEST_VERSION" "$package" "${args[@]}"
  fi
}

install_binaries cherry-test "$CHERRY_TEST_PATH"

case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *)
    echo "add $INSTALL_DIR to your PATH, for example:"
    printf '  export PATH="%s:$PATH"\n' "$INSTALL_DIR"
    ;;
esac

echo "installed cherry-test to $INSTALL_DIR/cherry-test"
