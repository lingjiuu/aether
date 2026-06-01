#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="${repo_root}/target/aether-jvm-runtime"
modules="java.se,java.net.http,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.management"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --output)
      output="$2"
      shift 2
      ;;
    --modules)
      modules="$2"
      shift 2
      ;;
    *)
      echo "Unexpected argument: $1" >&2
      exit 1
      ;;
  esac
done

find_jlink() {
  if [ -n "${JAVA_HOME:-}" ]; then
    for candidate in "$JAVA_HOME/bin/jlink" "$JAVA_HOME/bin/jlink.exe"; do
      if [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done
  fi

  if command -v jlink >/dev/null 2>&1; then
    command -v jlink
    return 0
  fi

  if command -v jlink.exe >/dev/null 2>&1; then
    command -v jlink.exe
    return 0
  fi

  return 1
}

jlink_bin="$(find_jlink || true)"
if [ -z "$jlink_bin" ]; then
  echo "jlink is required. Run this with a JDK 21 installation, not a JRE." >&2
  exit 1
fi

mkdir -p "$(dirname "$output")"
rm -rf "$output"

echo "Building bundled JVM runtime at ${output}..." >&2
echo "jlink: ${jlink_bin}" >&2
echo "modules: ${modules}" >&2
"$jlink_bin" \
  --add-modules "$modules" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --output "$output" >&2

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) java_bin="$output/bin/java.exe" ;;
  *) java_bin="$output/bin/java" ;;
esac

if [ ! -f "$java_bin" ]; then
  echo "Expected bundled Java launcher was not created: $java_bin" >&2
  exit 1
fi

echo "Bundled JVM runtime built: $output" >&2
echo "$output"
