package com.realityforge.scanner.data.importing

import android.content.ContentResolver
import android.net.Uri
import com.realityforge.scanner.capture.metadata.CaptureMetadata
import com.realityforge.scanner.capture.metadata.CaptureMetadataMerger
import com.realityforge.scanner.capture.metadata.CaptureSource
import com.realityforge.scanner.capture.metadata.ExifCaptureMetadataAdapter
import com.realityforge.scanner.capture.metadata.FieldEvidence
import com.realityforge.scanner.capture.metadata.EvidenceOrigin
import com.realityforge.scanner.capture.metadata.PixelSize
import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.imaging.AndroidImageDecoder
import com.realityforge.scanner.imaging.DecodeRequest
import java.io.File
import java.io.InputStream

class AndroidImportSourceAccess(private val resolver: ContentResolver) : ImportSourceAccess {
    override fun open(source: ImportSource): InputStream =
        requireNotNull(resolver.openInputStream(Uri.parse(source.uri))) { "Cannot open this photograph." }
}

/** Decode and metadata inspection borrow staged bytes and never rewrite EXIF or original pixels. */
object AndroidImportInspector : ImportInspector {
    override fun inspect(file: File, source: ImportSource): ImportedPhotoInfo {
        val request = DecodeRequest(file)
        val bounds = AndroidImageDecoder.inspect(request)
        val quality = AndroidImageDecoder.decodeBlocking(request).use { owned ->
            owned.withBitmap { ImageLoader.quality(it) }
        }
        val fallback = CaptureMetadata.empty(CaptureSource.IMPORTED_NO_METADATA).copy(
            imageSize = FieldEvidence.observed(PixelSize(bounds.width, bounds.height),
                EvidenceOrigin.CALLER_IMAGE_DIMENSIONS),
        )
        val exif = runCatching {
            ExifCaptureMetadataAdapter.read(file, PixelSize(bounds.width, bounds.height))
        }.getOrDefault(fallback)
        val metadata = source.captureMetadata?.let { CaptureMetadataMerger.merge(exif, it) } ?: exif
        require(quality.size >= 5 && quality.all { it.isFinite() }) { "Photo analysis did not produce valid evidence." }
        return ImportedPhotoInfo(bounds.width, bounds.height, ImageLoader.focal35(file),
            quality[2], quality[3], quality[4], metadata,
            java.util.Collections.unmodifiableMap(LinkedHashMap(source.captureSettings)))
    }
}
