package com.realityforge.scanner.agents.a012

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.imaging.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Synthetic JPEG header inspection must describe the same orientation as actual Android decode. */
class ImageInspectionInstrumentedTest {
    private fun jpeg(orientation:Int?):File {
        val file=File.createTempFile("rf-inspect-",".jpg",InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val bitmap=Bitmap.createBitmap(80,64,Bitmap.Config.ARGB_8888)
        try {file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}}
        finally {bitmap.recycle()}
        if(orientation!=null)ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
        return file
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    @Test fun allEightOrientationsAgreeWithDecodedPixelsAndPreserveOriginal() {
        for(tag in 1..8) {
            val file=jpeg(tag)
            try {
                val before=hash(file)
                for(apply in listOf(true,false)) {
                    val request=DecodeRequest(file,applyExifOrientation=apply)
                    val inspected=AndroidImageDecoder.inspectUpright(request)
                    assertEquals(80 to 64,inspected.source.width to inspected.source.height)
                    val expected=if(apply && tag>=5)64 to 80 else 80 to 64
                    assertEquals(expected,inspected.uprightWidth to inspected.uprightHeight)
                    AndroidImageDecoder.decodeBlocking(request).use {decoded->
                        assertEquals(expected,decoded.width to decoded.height)
                        assertEquals(inspected.orientation,decoded.info.orientation)
                        assertEquals(inspected.orientationEvidence,decoded.info.orientationEvidence)
                        assertEquals(inspected.warnings,decoded.info.warnings)
                    }
                }
                assertArrayEquals(before,hash(file))
            }finally{file.delete()}
        }
    }
    @Test fun absentAndMalformedOrientationKeepExistingDecoderPolicy() {
        for(tag in listOf(null,999)) {
            val file=jpeg(tag)
            try {
                val request=DecodeRequest(file)
                val inspected=AndroidImageDecoder.inspectUpright(request)
                assertEquals(ExifOrientation.NORMAL,inspected.orientation)
                assertEquals(if(tag==null)OrientationEvidence.ABSENT else OrientationEvidence.MALFORMED,inspected.orientationEvidence)
                AndroidImageDecoder.decodeBlocking(request).use {decoded->
                    assertEquals(inspected.uprightWidth to inspected.uprightHeight,decoded.width to decoded.height)
                    assertEquals(inspected.orientationEvidence,decoded.info.orientationEvidence)
                    assertEquals(inspected.warnings,decoded.info.warnings)
                }
            }finally{file.delete()}
        }
    }
    @Test fun missingCorruptAndOverBudgetFilesNeverYieldGeometry() {
        val root=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val missing=File(root,"missing-${java.util.UUID.randomUUID()}.jpg")
        assertEquals(ImageDecodeFailure.FILE_NOT_FOUND,assertThrows(ImageDecodeException::class.java){
            AndroidImageDecoder.inspectUpright(DecodeRequest(missing))}.failure)
        val corrupt=File.createTempFile("rf-corrupt-",".jpg",root)
        try {
            corrupt.writeBytes(byteArrayOf(1,2,3))
            assertEquals(ImageDecodeFailure.CORRUPT_IMAGE,assertThrows(ImageDecodeException::class.java){
                AndroidImageDecoder.inspectUpright(DecodeRequest(corrupt))}.failure)
        }finally{corrupt.delete()}
        val valid=jpeg(6)
        try {assertEquals(ImageDecodeFailure.ENCODED_SIZE_LIMIT,assertThrows(ImageDecodeException::class.java){
            AndroidImageDecoder.inspectUpright(DecodeRequest(valid,maxEncodedBytes=1))}.failure)}
        finally{valid.delete()}
    }
}
