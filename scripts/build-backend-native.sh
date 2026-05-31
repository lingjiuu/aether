#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v native-image >/dev/null 2>&1; then
  echo "native-image is required. Install GraalVM Native Image or run this in the GitHub Actions release workflow." >&2
  exit 1
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) exe_suffix=".exe" ;;
  *) exe_suffix="" ;;
esac

(
  cd "$repo_root"
  mvn -Pnative -DskipTests native:compile
) >&2

binary="${repo_root}/target/aether-backend${exe_suffix}"
if [ ! -f "$binary" ]; then
  echo "Expected native backend was not created: $binary" >&2
  exit 1
fi

if [ -z "$exe_suffix" ]; then
  chmod +x "$binary"
fi

echo "$binary"
