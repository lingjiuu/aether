#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

has_native_image() {
  command -v native-image >/dev/null 2>&1 \
    || command -v native-image.cmd >/dev/null 2>&1 \
    || command -v native-image.exe >/dev/null 2>&1 \
    || native_image_exists_in_home "${JAVA_HOME:-}" \
    || native_image_exists_in_home "${GRAALVM_HOME:-}"
}

native_image_exists_in_home() {
  local home="$1"
  if [ -z "$home" ]; then
    return 1
  fi
  if command -v cygpath >/dev/null 2>&1; then
    home="$(cygpath -u "$home" 2>/dev/null || printf '%s' "$home")"
  fi
  [ -f "$home/bin/native-image" ] \
    || [ -f "$home/bin/native-image.cmd" ] \
    || [ -f "$home/bin/native-image.exe" ]
}

if ! has_native_image; then
  echo "native-image is required. Install GraalVM Native Image or run this in the GitHub Actions release workflow." >&2
  exit 1
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    exe_suffix=".exe"
    export _CL_="${_CL_:+${_CL_} }/MT"
    echo "Configuring MSVC static runtime via _CL_=${_CL_}" >&2
    ;;
  *) exe_suffix="" ;;
esac

echo "Building GraalVM native backend in ${repo_root}..." >&2
echo "Platform: $(uname -s) $(uname -m)" >&2
echo "JAVA_HOME: ${JAVA_HOME:-<unset>}" >&2
native-image --version >&2 || native-image.cmd --version >&2 || native-image.exe --version >&2 || true
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

echo "Native backend built: $binary" >&2
echo "$binary"
