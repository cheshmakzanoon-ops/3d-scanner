package com.realityforge.scanner.engine.pipeline

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

private const val MAX_COMPLETE_NATIVE_REFERENCES = 8

/** Select optional complete native references, not tile/partial cache work or accepted geometry.
 * Native dense.hpp MAX_DENSE_VIEWS=8; depth_checkpoint.cpp load/store reject indices >=8.
 * Fixed lookups avoid enumerating up to4096 tile files per reference. Native loading remains
 * authoritative for binary validity; PipelineArtifactSession checks all ancestor ownership.
 */
internal fun completeNativeDepthReferences(directory: File): List<File> {
    if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return (0 until MAX_COMPLETE_NATIVE_REFERENCES).map { File(directory, "ref-$it.bin") }
        .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
}
