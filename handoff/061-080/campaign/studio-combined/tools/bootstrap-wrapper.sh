#!/usr/bin/env bash
# First build only. Downloads the official Gradle 8.13 wrapper and validates its published checksum.
# Runtime scanning does not execute this script and requires no network permission.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
folder="$root/gradle/wrapper"
mkdir -p "$folder"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl --fail --location --retry 2 --connect-timeout 20 --max-time 180 \
  https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar -o "$tmp/wrapper.jar"
curl --fail --location --retry 2 --max-time 60 \
  https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256 -o "$tmp/wrapper.sha256"
curl --fail --location --retry 2 --max-time 60 \
  https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256 -o "$tmp/distribution.sha256"
expected=$(tr -d '\r\n ' < "$tmp/wrapper.sha256")
distribution=$(tr -d '\r\n ' < "$tmp/distribution.sha256")
[[ "$expected" =~ ^[a-fA-F0-9]{64}$ && "$distribution" =~ ^[a-fA-F0-9]{64}$ ]] || { echo "Invalid checksum response" >&2; exit 1; }
if command -v sha256sum >/dev/null; then actual=$(sha256sum "$tmp/wrapper.jar" | cut -d' ' -f1)
else actual=$(shasum -a 256 "$tmp/wrapper.jar" | cut -d' ' -f1); fi
[[ "$actual" == "$expected" ]] || { echo "Gradle wrapper checksum mismatch" >&2; exit 1; }
grep -v '^distributionSha256Sum=' "$folder/gradle-wrapper.properties" > "$tmp/properties"
printf '\ndistributionSha256Sum=%s\n' "$distribution" >> "$tmp/properties"
mv "$tmp/properties" "$folder/gradle-wrapper.properties"
printf '%s\n' "$expected" > "$folder/gradle-wrapper.jar.sha256"
mv "$tmp/wrapper.jar" "$folder/gradle-wrapper.jar"
echo "Official Gradle wrapper downloaded and checksum verified."
