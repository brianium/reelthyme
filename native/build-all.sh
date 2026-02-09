#!/usr/bin/env bash
#
# Cross-compile libreelthyme_aec for all supported targets using Zig.
# Outputs go to out/{os}-{arch}/

set -euo pipefail
cd "$(dirname "$0")"

TARGETS=(
  "aarch64-macos:darwin-aarch64:lib:dylib"
  "x86_64-macos:darwin-x86_64:lib:dylib"
  "aarch64-linux-gnu:linux-aarch64:lib:so"
  "x86_64-linux-gnu:linux-x86_64:lib:so"
  "x86_64-windows-gnu:windows-x86_64::dll"
)

for entry in "${TARGETS[@]}"; do
  IFS=: read -r zig_target out_dir prefix ext <<< "$entry"
  echo "=== Building for ${zig_target} ==="

  zig build -Dtarget="${zig_target}" -Doptimize=ReleaseFast \
    --prefix "out/${out_dir}" 2>&1

  echo "    -> out/${out_dir}/"
done

echo ""
echo "=== Copying to resources/native/ ==="

RESOURCE_BASE="../resources/native"

for entry in "${TARGETS[@]}"; do
  IFS=: read -r zig_target out_dir prefix ext <<< "$entry"
  src_dir="out/${out_dir}/lib"
  dest_dir="${RESOURCE_BASE}/${out_dir}"
  mkdir -p "${dest_dir}"

  if [ "${ext}" = "dylib" ]; then
    cp "${src_dir}/${prefix}reelthyme_aec.${ext}" "${dest_dir}/"
  elif [ "${ext}" = "so" ]; then
    cp "${src_dir}/${prefix}reelthyme_aec.${ext}" "${dest_dir}/"
  elif [ "${ext}" = "dll" ]; then
    cp "out/${out_dir}/bin/reelthyme_aec.${ext}" "${dest_dir}/"
  fi

  echo "  ${dest_dir}/"
done

echo ""
echo "Done. Pre-built binaries are in ${RESOURCE_BASE}/"
