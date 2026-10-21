#!/usr/bin/env python3
"""Verify the committed official Gradle 8.13 wrapper and distribution pin, offline."""
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
DISTRIBUTION_SHA256 = "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
if not jar.is_file():
    raise SystemExit("Missing committed wrapper JAR; restore it from Git or tools/bootstrap-wrapper.sh.")
if hashlib.sha256(jar.read_bytes()).hexdigest() != WRAPPER_SHA256:
    raise SystemExit("Gradle wrapper JAR checksum mismatch.")
properties = dict(line.split("=", 1) for line in
                  (jar.parent / "gradle-wrapper.properties").read_text().splitlines()
                  if line and not line.startswith("#") and "=" in line)
if properties.get("distributionSha256Sum") != DISTRIBUTION_SHA256:
    raise SystemExit("Gradle distribution checksum is absent or changed.")
if not properties.get("distributionUrl", "").endswith("/gradle-8.13-bin.zip"):
    raise SystemExit("Gradle distribution version differs from the verified pin.")
print("Verified official Gradle 8.13 wrapper and distribution SHA-256 pins.")
