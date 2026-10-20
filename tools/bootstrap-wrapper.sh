#!/usr/bin/env bash
# Explicit repair only. Normal builds use the verified wrapper committed to Git.
set -euo pipefail
rf_root=$(cd "$(dirname "$0")/.." && pwd)
rf_folder="$rf_root/gradle/wrapper"
rf_expected=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
rf_temporary=$(mktemp -d)
trap 'rm -rf "$rf_temporary"' EXIT
curl --fail --location --retry 2 --connect-timeout 20 --max-time 180 \
  https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar -o "$rf_temporary/wrapper.jar"
if command -v sha256sum >/dev/null; then
  rf_actual=$(sha256sum "$rf_temporary/wrapper.jar" | cut -d' ' -f1)
else
  rf_actual=$(shasum -a 256 "$rf_temporary/wrapper.jar" | cut -d' ' -f1)
fi
[[ "$rf_actual" == "$rf_expected" ]] || { echo "Gradle wrapper checksum mismatch" >&2; exit 1; }
mv "$rf_temporary/wrapper.jar" "$rf_folder/gradle-wrapper.jar"
echo "Pinned Gradle 8.13 wrapper restored and verified. Distribution settings unchanged."
