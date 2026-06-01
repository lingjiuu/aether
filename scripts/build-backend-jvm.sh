#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Building JVM backend jar in ${repo_root}..." >&2
echo "Platform: $(uname -s) $(uname -m)" >&2
echo "JAVA_HOME: ${JAVA_HOME:-<unset>}" >&2
java -version >&2
(
  cd "$repo_root"
  mvn -DskipTests package
) >&2

jar="${repo_root}/target/aether-backend.jar"
if [ ! -f "$jar" ]; then
  echo "Expected JVM backend jar was not created: $jar" >&2
  exit 1
fi

echo "JVM backend jar built: $jar" >&2
echo "$jar"
