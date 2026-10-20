# Agent 004: bounded PNG compression dependency

Agent 057 may use the maintained zlib streaming API for photographic PNG
encoding. `realityforge` links `ZLIB::ZLIB`; Android resolves the public platform
libz through the pinned NDK 28.2.13676358, and host builds resolve the installed
development package through CMake's FindZLIB. No library is fetched or unpacked
by the installed application.

The onboarding Debian host provides zlib 1.3.1 from the distribution packages
`zlib1g` and `zlib1g-dev` (`1:1.3.dfsg+really1.3.1-1+b1`). The zlib license is
permissive; distribution copyright/license metadata is available at
`/usr/share/doc/zlib1g/copyright`, and the Android NDK supplies platform headers
and import libraries. No new third-party source or binary is vendored by this
change. Agent 095 retains ownership of the final complete license/SBOM inventory.

The encoder must bound simultaneous buffers, keep `deflateEnd` exception-safe,
preserve cancellation, propagate sink failures, and validate lossless output
with an independent decoder. Compressed-byte identity is tied to the selected
toolchain/zlib version; decoded RGBA equality is the interoperability contract.
