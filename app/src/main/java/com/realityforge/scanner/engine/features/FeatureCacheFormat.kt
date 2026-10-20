package com.realityforge.scanner.engine.features

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Decoder and extractor identities are exact canonical hashes supplied by their owners. */
data class FeatureCacheIdentity(val originalSha256:String,val decoderTransform:String,val extractorSettings:String,val openCvVersion:String) {
    init {
        require(originalSha256.matches(Regex("[a-f0-9]{64}")))
        require(listOf(decoderTransform,extractorSettings,openCvVersion).all{it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size<=4096})
    }
}
class CachedFeatures(val identity:FeatureCacheIdentity,val width:Int,val height:Int,xy:FloatArray,colors:IntArray,descriptors:FloatArray) {
    private val positions=xy.copyOf();private val argb=colors.copyOf();private val values=descriptors.copyOf()
    val count get()=argb.size
    init {
        require(width in 3..4096 && height in 3..4096 && count in 0..20000)
        require(positions.size==count*2 && values.size==count*128)
        require(positions.indices.all{i->positions[i].isFinite() && positions[i]>=0 && positions[i]<if(i%2==0)width else height})
        require(values.all{it.isFinite() && it>=0f && it<=512f}) { "Invalid SIFT descriptor" }
    }
    fun coordinates()=positions.copyOf()
    fun colors()=argb.copyOf()
    fun descriptors()=values.copyOf()
}

object FeatureCacheFormat {
    const val VERSION=2
    const val MAX_BYTES=12*1024*1024
    private const val MAGIC=0x52464643
    fun encode(record:CachedFeatures):ByteArray {
        val payload=ByteArrayOutputStream()
        DataOutputStream(payload).use{out->
            listOf(record.identity.originalSha256,record.identity.decoderTransform,record.identity.extractorSettings,record.identity.openCvVersion).forEach { s->
                val b=s.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b)
            }
            out.writeInt(record.width);out.writeInt(record.height);out.writeInt(record.count);out.writeInt(128)
            record.coordinates().forEach(out::writeFloat);record.colors().forEach(out::writeInt);record.descriptors().forEach(out::writeFloat)
        }
        val body=payload.toByteArray();require(body.size+44<=MAX_BYTES)
        return ByteArrayOutputStream().also{stream->DataOutputStream(stream).use{out->
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(body.size);out.write(sha(body));out.write(body)
        }}.toByteArray()
    }
    fun decode(bytes:ByteArray,expected:FeatureCacheIdentity):CachedFeatures {
        require(bytes.size in 44..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{input->
            require(input.readInt()==MAGIC && input.readInt()==VERSION) { "Unsupported feature cache" }
            val size=input.readInt();require(size==bytes.size-44)
            val digest=ByteArray(32).also(input::readFully);val body=ByteArray(size).also(input::readFully)
            require(MessageDigest.isEqual(digest,sha(body))) { "Feature payload checksum mismatch" }
            DataInputStream(ByteArrayInputStream(body)).use{data->
                fun string():String {
                    val n=data.readInt();require(n in 1..4096 && n<=data.available())
                    val raw=ByteArray(n).also(data::readFully)
                    val text=raw.toString(Charsets.UTF_8)
                    require(text.toByteArray(Charsets.UTF_8).contentEquals(raw)) { "Malformed UTF-8" }
                    return text
                }
                val identity=FeatureCacheIdentity(string(),string(),string(),string());require(identity==expected) { "Stale feature identity" }
                val width=data.readInt();val height=data.readInt();val count=data.readInt();val columns=data.readInt()
                require(width in 3..4096 && height in 3..4096 && count in 0..20000 && columns==128)
                require(data.available().toLong()==count*524L) { "Feature shape/length mismatch" }
                val xy=FloatArray(count*2){data.readFloat()};val colors=IntArray(count){data.readInt()};val desc=FloatArray(count*128){data.readFloat()}
                CachedFeatures(identity,width,height,xy,colors,desc)
            }
        }
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
}
