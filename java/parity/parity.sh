#!/usr/bin/env bash
{
set -euo pipefail

usage() {
  echo "usage: $0 record|replay|compare|pixels|replay-rust|compare-rust <app dir, absolute or relative to the repository root> [filter]" >&2
  exit 2
}

[ $# -eq 2 ] || [ $# -eq 3 ] || usage
mode=$1
app_relative=${2%/}
filter=${3:-}

repository=$(cd "$(dirname "$0")/../.." && pwd)
parity="$repository/java/parity"
case "$app_relative" in
  /*) app=$app_relative ;;
  *) app="$repository/$app_relative" ;;
esac
name=${PARITY_NAME:-$(basename "$app_relative")}
work="$parity/target/work/$name${filter:+-$filter}"
shims="$parity/target/shims/$name${filter:+-$filter}"
cassette="$work/cassette"
rust_binary="$parity/target/rust/release/cherry-test"
java_jar="$repository/java/cherry-test-cli/target/cherry-test-java.jar"
parity_jar="$parity/target/cherry-test-parity.jar"
java_binary=$(command -v java)
tools="cargo node pnpm npx git docker mvn gradle cmake ctest llvm-profdata llvm-cov python3"
host_tools="$parity/target/host-tools"
extra_path=""
if [ -f "$app/pom.xml" ]; then
  tools="$tools sh"
  extra_path="$host_tools:"
fi
runs="$app/target/cherry-test/runs"
latest="$app/target/cherry-test/latest"

[ -d "$app" ] || { echo "no such app: $app" >&2; exit 2; }

write_shims() {
  rm -rf "$shims"
  mkdir -p "$shims"
  cp "$parity_jar" "$shims/parity.jar"
  for tool in $tools; do
    printf '#!/bin/sh\nexec "%s" -cp "%s" com.cherrytest.parity.Shim %s "$@"\n' "$java_binary" "$shims/parity.jar" "$tool" >"$shims/$tool"
    chmod +x "$shims/$tool"
  done
  if [ -n "$extra_path" ]; then
    mkdir -p "$host_tools"
    printf '#!/bin/sh\nexec "%s/java/mvnw" "$@"\n' "$repository" >"$host_tools/mvn"
    chmod +x "$host_tools/mvn"
  fi
}

okta_ports() {
  "$java_binary" -cp "$parity_jar" com.cherrytest.parity.OktaPorts "$cassette"
}

start_auth_stub() {
  auth_stub_pid=""
  if [ -s "$cassette/auth.json" ]; then
    ports=$(okta_ports)
    if [ -n "$ports" ]; then
      "$java_binary" -cp "$parity_jar" com.cherrytest.parity.AuthStub "$cassette/auth.json" $ports >"$work/auth-stub.log" 2>&1 &
      auth_stub_pid=$!
      until grep -q "listening" "$work/auth-stub.log" 2>/dev/null; do sleep 0.2; done
    fi
  fi
}

stop_auth_stub() {
  if [ -n "${auth_stub_pid:-}" ]; then
    kill "$auth_stub_pid" 2>/dev/null || :
    wait "$auth_stub_pid" 2>/dev/null || :
  fi
}


shimmed() {
  local shim_mode=$1
  shift
  env CHERRY_PARITY_MODE="$shim_mode" \
    CHERRY_PARITY_SHIM_DIR="$shims" \
    CHERRY_PARITY_CASSETTE="$cassette" \
    CHERRY_PARITY_ROOT="$app" \
    CHERRY_PARITY_NVM_BIN="${NVM_BIN:-}" \
    PATH="$shims:$extra_path$PATH" \
    NVM_BIN="$shims" \
    "$@"
}

archive_run() {
  local run_id=$1 label=$2
  rm -rf "${work:?}/$label-run"
  if [ -d "$runs/$run_id" ]; then
    mv "$runs/$run_id" "$work/$label-run"
  fi
  if [ -L "$latest" ]; then
    readlink "$latest" >"$work/$label.latest"
    rm "$latest"
  else
    printf 'missing\n' >"$work/$label.latest"
  fi
}

remember_latest() {
  if [ -L "$latest" ] && [ ! -e "$work/original.latest" ]; then
    readlink "$latest" >"$work/original.latest"
  fi
}

restore_latest() {
  if [ -s "$work/original.latest" ] && [ ! -e "$latest" ]; then
    ln -s "$(cat "$work/original.latest")" "$latest"
  fi
}

reset_replay_state() {
  rm -f "$cassette/replay-state.txt" "$cassette/replay-misses.log" "$cassette/replay-environment.log"
}

refuse_concurrent_runs() {
  if pgrep -f -- "--root $app_relative --no-open" >/dev/null; then
    echo "a Cherry Test run on $app_relative is still in progress; stop it before recording or replaying" >&2
    exit 1
  fi
}

cd "$repository"
case "$mode" in
  record)
    refuse_concurrent_runs
    rm -rf "$work"
    mkdir -p "$work"
    write_shims
    remember_latest
    set +e
    shimmed record "$rust_binary" --root "$app_relative" --no-open ${filter:+"$filter"} </dev/null >"$work/rust.stdout" 2>"$work/rust.stderr"
    echo $? >"$work/rust.exit"
    set -e
    run_id=$(sed -n 's/^cherry-test run: \([^ ]*\) .*/\1/p' "$work/rust.stdout" | head -n 1)
    [ -n "$run_id" ] || { echo "no run id in rust output" >&2; exit 1; }
    echo "$run_id" >"$work/run-id"
    archive_run "$run_id" rust
    restore_latest
    echo "recorded $name run $run_id (exit $(cat "$work/rust.exit"), $(wc -l <"$cassette/index.jsonl") tool calls)"
    ;;
  replay)
    refuse_concurrent_runs
    run_id=$(cat "$work/run-id")
    [ ! -e "$runs/$run_id" ] || { echo "run dir $runs/$run_id still exists" >&2; exit 1; }
    reset_replay_state
    write_shims
    start_auth_stub
    set +e
    shimmed replay "$java_binary" -Dcherrytest.runId="$run_id" --enable-native-access=ALL-UNNAMED -jar "$java_jar" \
      --root "$app_relative" --no-open ${filter:+"$filter"} </dev/null >"$work/java.stdout" 2>"$work/java.stderr"
    echo $? >"$work/java.exit"
    set -e
    stop_auth_stub
    archive_run "$run_id" java
    restore_latest
    cp "$cassette/replay-state.txt" "$work/java.replay-state" 2>/dev/null || : >"$work/java.replay-state"
    echo "replayed $name run $run_id with the Java CLI (exit $(cat "$work/java.exit"))"
    ;;
  replay-rust)
    refuse_concurrent_runs
    reset_replay_state
    write_shims
    start_auth_stub
    set +e
    shimmed replay "$rust_binary" --root "$app_relative" --no-open ${filter:+"$filter"} </dev/null >"$work/rust-replay.stdout" 2>"$work/rust-replay.stderr"
    echo $? >"$work/rust-replay.exit"
    set -e
    stop_auth_stub
    replay_id=$(sed -n 's/^cherry-test run: \([^ ]*\) .*/\1/p' "$work/rust-replay.stdout" | head -n 1)
    echo "$replay_id" >"$work/rust-replay.run-id"
    archive_run "$replay_id" rust-replay
    restore_latest
    cp "$cassette/replay-state.txt" "$work/rust-replay.replay-state" 2>/dev/null || : >"$work/rust-replay.replay-state"
    echo "replayed $name with the Rust CLI as run $replay_id (exit $(cat "$work/rust-replay.exit"))"
    ;;
  pixels)
    chromium=${CHERRY_PARITY_CHROMIUM:-$HOME/Library/Caches/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-mac-arm64/chrome-headless-shell}
    rm -rf "$parity/target/parity-report/$name${filter:+-$filter}"
    "$java_binary" -cp "$parity_jar:$(cat "$parity/target/pixels.classpath")" com.cherrytest.parity.Pixels \
      "$work/rust-run/reports" "$work/java-run/reports" "$parity/target/parity-report/$name${filter:+-$filter}" "$chromium"
    ;;
  compare-rust)
    status=0
    run_id=$(cat "$work/run-id")
    replay_id=$(cat "$work/rust-replay.run-id")
    for stream in stdout stderr exit; do
      if cmp -s "$work/rust.$stream" <(sed "s/$replay_id/$run_id/g" "$work/rust-replay.$stream"); then
        echo "IDENTICAL $stream"
      else
        echo "DIFFERENT $stream"
        diff "$work/rust.$stream" <(sed "s/$replay_id/$run_id/g" "$work/rust-replay.$stream") | head -n 40 || :
        status=1
      fi
    done
    "$java_binary" -cp "$parity_jar" com.cherrytest.parity.Orchestration "$cassette" "$work/rust-replay.replay-state" || status=1
    "$java_binary" -cp "$parity_jar" com.cherrytest.parity.Compare "$work/rust-run" "$work/rust-replay-run" "$run_id" "$replay_id" || status=1
    exit $status
    ;;
  compare)
    status=0
    for stream in stdout stderr exit latest; do
      if cmp -s "$work/rust.$stream" "$work/java.$stream"; then
        echo "IDENTICAL $stream"
      else
        echo "DIFFERENT $stream"
        diff "$work/rust.$stream" "$work/java.$stream" | head -n 40 || :
        status=1
      fi
    done
    for log in replay-misses.log replay-environment.log; do
      if [ -s "$cassette/$log" ]; then
        echo "FAILED orchestration: $log"
        head -n 40 "$cassette/$log"
        status=1
      fi
    done
    "$java_binary" -cp "$parity_jar" com.cherrytest.parity.Orchestration "$cassette" "$work/java.replay-state" || status=1
    "$java_binary" -cp "$parity_jar" com.cherrytest.parity.Compare "$work/rust-run" "$work/java-run" || status=1
    exit $status
    ;;
  *)
    usage
    ;;
esac
exit
}
