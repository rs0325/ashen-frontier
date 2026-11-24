#!/usr/bin/env bash
# 診断専用。試験結果は変更せず、worldの全成功後も残るJVMの状態を記録する。
set -u
log=$1
out=$2
mkdir -p "$out"
while ! grep -q 'All 85 required tests passed' "$log" 2>/dev/null; do
  sleep 2
done
sleep 30
for round in 1 2; do
  echo "=== GAMETEST SHUTDOWN DIAGNOSTIC round=$round $(date -u +%FT%TZ) ==="
  for pid in $(pgrep -x java || true); do
    java_exe=$(readlink -f "/proc/$pid/exe") || continue
    jcmd_bin="${java_exe%/*}/jcmd"
    if [[ ! -x "$jcmd_bin" ]]; then
      jcmd_bin="$JAVA_HOME/bin/jcmd"
    fi
    echo "=== JVM pid=$pid executable=$java_exe ==="
    for command in VM.command_line Thread.print; do
      args=("$command")
      [[ "$command" != Thread.print ]] || args+=(-l)
      timeout --foreground 20s "$jcmd_bin" "$pid" "${args[@]}" 2>&1 |
        tee "$out/$pid-$round-$command.txt" || true
    done
  done
  [[ $round == 2 ]] || sleep 30
done
