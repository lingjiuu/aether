#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bundle_dir="${AETHER_EVAL_BUNDLE_DIR:-${repo_root}/evals/.bundle/aether-eval}"
eval_config="${AETHER_EVAL_CONFIG_SOURCE:-${repo_root}/evals/aether-eval.toml}"
jre_arch="${AETHER_EVAL_JRE_ARCH:-all}"
skip_maven=0
force_jre=0

usage() {
  cat <<'EOF'
Usage:
  evals/build-aether-eval-bundle.sh [options]

Options:
  --bundle-dir PATH       Output directory. Defaults to evals/.bundle/aether-eval.
  --eval-config PATH      Eval config to copy into the bundle. Defaults to evals/aether-eval.toml.
  --jre-arch VALUE        all, current, linux-x64, linux-arm64, or none. Defaults to all.
  --skip-maven            Reuse target/classes and target/eval-bundle/lib.
  --force-jre             Re-download selected JREs.
  -h, --help              Show this help.

The bundle contains:
  bin/aether-eval         Shell wrapper used inside Terminal-Bench task containers.
  app/classes             Compiled Aether classes and resources.
  app/lib                 Maven runtime dependency jars.
  jre/linux-*             Downloaded Temurin JRE 21 runtimes.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --bundle-dir)
      bundle_dir="$2"
      shift 2
      ;;
    --bundle-dir=*)
      bundle_dir="${1#*=}"
      shift
      ;;
    --jre-arch)
      jre_arch="$2"
      shift 2
      ;;
    --jre-arch=*)
      jre_arch="${1#*=}"
      shift
      ;;
    --eval-config)
      eval_config="$2"
      shift 2
      ;;
    --eval-config=*)
      eval_config="${1#*=}"
      shift
      ;;
    --skip-maven)
      skip_maven=1
      shift
      ;;
    --force-jre)
      force_jre=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

mkdir -p "$(dirname "$bundle_dir")"
bundle_dir="$(cd "$(dirname "$bundle_dir")" && pwd)/$(basename "$bundle_dir")"
build_dir="${repo_root}/target/eval-bundle"

if [ "$skip_maven" -eq 0 ]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "mvn is required to build the eval bundle." >&2
    exit 1
  fi
  echo "Building Aether classes and runtime dependencies..."
  rm -rf "$build_dir"
  mkdir -p "$build_dir/lib"
  (
    cd "$repo_root"
    mvn -q -DskipTests package dependency:copy-dependencies \
      -DincludeScope=runtime \
      -DoutputDirectory="$build_dir/lib"
  )
fi

if [ ! -d "${repo_root}/target/classes" ]; then
  echo "target/classes does not exist. Run without --skip-maven first." >&2
  exit 1
fi
if [ ! -d "$build_dir/lib" ]; then
  echo "$build_dir/lib does not exist. Run without --skip-maven first." >&2
  exit 1
fi
if [ ! -f "$eval_config" ]; then
  echo "Eval config does not exist: $eval_config" >&2
  if [ "$eval_config" = "${repo_root}/evals/aether-eval.toml" ]; then
    echo "Copy evals/aether-eval.example.toml to evals/aether-eval.toml and fill in the eval settings." >&2
  fi
  exit 1
fi

rm -rf "${bundle_dir}/app" "${bundle_dir}/bin"
mkdir -p "${bundle_dir}/app/classes" "${bundle_dir}/app/lib" "${bundle_dir}/bin" "${bundle_dir}/config"
cp -R "${repo_root}/target/classes/." "${bundle_dir}/app/classes/"
cp "${build_dir}/lib/"*.jar "${bundle_dir}/app/lib/"
cp "$eval_config" "${bundle_dir}/config/aether-eval.toml"

cat > "${bundle_dir}/bin/aether-eval" <<'EOF'
#!/usr/bin/env sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
platform=""
if [ "$(uname -s)" = "Linux" ]; then
  machine="$(uname -m)"
  case "$machine" in
    x86_64|amd64) platform="linux-x64" ;;
    aarch64|arm64) platform="linux-arm64" ;;
  esac
fi

java_bin="${JAVA:-}"
if [ -z "$java_bin" ] && [ -n "$platform" ] && [ -x "$root/jre/$platform/bin/java" ]; then
  java_bin="$root/jre/$platform/bin/java"
fi
if [ -z "$java_bin" ]; then
  java_bin="java"
fi
if [ -z "${AETHER_EVAL_CONFIG:-}" ] && [ -f "$root/config/aether-eval.toml" ]; then
  AETHER_EVAL_CONFIG="$root/config/aether-eval.toml"
  export AETHER_EVAL_CONFIG
fi

exec "$java_bin" ${AETHER_EVAL_JAVA_OPTS:-} \
  -cp "$root/app/classes:$root/app/lib/*" \
  io.github.lingjiuu.eval.EvalRunner "$@"
EOF
chmod +x "${bundle_dir}/bin/aether-eval"

current_linux_platform() {
  machine="$(uname -m)"
  case "$machine" in
    x86_64|amd64) echo "linux-x64" ;;
    aarch64|arm64) echo "linux-arm64" ;;
    *) echo "Unsupported architecture for current JRE bundle: $machine" >&2; exit 1 ;;
  esac
}

adoptium_arch() {
  case "$1" in
    linux-x64) echo "x64" ;;
    linux-arm64) echo "aarch64" ;;
    *) echo "Unsupported JRE platform: $1" >&2; exit 1 ;;
  esac
}

download_jre() {
  platform="$1"
  dest="${bundle_dir}/jre/${platform}"
  if [ "$force_jre" -eq 0 ] && [ -x "${dest}/bin/java" ] && [ -f "${dest}/release" ]; then
    echo "JRE already present: ${platform}"
    return
  fi

  arch="$(adoptium_arch "$platform")"
  url="https://api.adoptium.net/v3/binary/latest/21/ga/linux/${arch}/jre/hotspot/normal/eclipse"
  tmp="${bundle_dir}/.tmp/jre-${platform}"
  echo "Downloading Temurin JRE 21 for ${platform}..."
  rm -rf "$tmp" "$dest"
  mkdir -p "$tmp" "$dest"
  curl -fsSL "$url" -o "${tmp}/jre.tar.gz"
  tar -xzf "${tmp}/jre.tar.gz" -C "$dest" --strip-components=1
  rm -rf "$tmp"
  if [ ! -x "${dest}/bin/java" ]; then
    echo "Downloaded JRE is missing bin/java: ${platform}" >&2
    exit 1
  fi
  if [ "$(uname -s)" = "Linux" ] && [ "$(current_linux_platform)" = "$platform" ]; then
    "${dest}/bin/java" -version >/dev/null
  fi
}

case "$jre_arch" in
  none)
    ;;
  current)
    download_jre "$(current_linux_platform)"
    ;;
  all)
    download_jre linux-x64
    download_jre linux-arm64
    ;;
  linux-x64|linux-arm64)
    download_jre "$jre_arch"
    ;;
  *)
    echo "Unknown --jre-arch value: $jre_arch" >&2
    exit 2
    ;;
esac

commit="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
built_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
cat > "${bundle_dir}/manifest.json" <<EOF
{
  "name": "aether-eval-bundle",
  "commit": "${commit}",
  "builtAt": "${built_at}",
  "javaMainClass": "io.github.lingjiuu.eval.EvalRunner",
  "bundleVersion": 1
}
EOF

echo "Eval bundle ready: ${bundle_dir}"
echo "Runner: ${bundle_dir}/bin/aether-eval"
