package com.realityforge.scanner.engine.tracks

import java.io.*
import java.security.MessageDigest
import java.util.Collections

/** Durable identities belong to source photographs, never the current native array order. */
internal data class SourceObservation(val photo: String, val keypoint: Int) : Comparable<SourceObservation> {
    init { require(photo.isNotBlank() && '\u0000' !in photo && photo.toByteArray(Charsets.UTF_8).size <= 1024 && photo.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==photo && keypoint in 0..1_000_000) }
    override fun compareTo(other: SourceObservation) = compareValuesBy(this, other, { it.photo }, { it.keypoint })
    private val stableIdentity: String by lazy { digest("observation-v1\u0000$photo\u0000$keypoint".toByteArray(Charsets.UTF_8)) }
    fun identity(): String = stableIdentity
}
internal fun digest(bytes: ByteArray): String {
    val hash=MessageDigest.getInstance("SHA-256").digest(bytes);val alphabet="0123456789abcdef";val out=CharArray(hash.size*2)
    for(i in hash.indices){val value=hash[i].toInt() and 255;out[i*2]=alphabet[value ushr 4];out[i*2+1]=alphabet[value and 15]}
    return String(out)
}
internal class StableTrack(val id: String, sourceObservations: List<SourceObservation>) {
    val observations: List<SourceObservation> = Collections.unmodifiableList(sourceObservations.toList())
    override fun equals(other: Any?) = other is StableTrack && id==other.id && observations==other.observations
    override fun hashCode() = 31*id.hashCode()+observations.hashCode()
    init {
        require(observations.size in 2..250 && observations == observations.sorted() && observations.distinct().size == observations.size)
        require(observations.map { it.photo }.distinct().size == observations.size)
        require(id == observations.minOf { it.identity() })
    }
}
/** Every source-observation identity aliases the current canonical track. A merge can
 * change the canonical minimum, but old published identities resolve without recycling. */
internal class TrackSnapshot(sourceTracks: List<StableTrack>) {
    val tracks: List<StableTrack> = Collections.unmodifiableList(sourceTracks.toList())
    override fun equals(other: Any?) = other is TrackSnapshot && tracks==other.tracks
    override fun hashCode() = tracks.hashCode()
    private val aliases: Map<String,String> by lazy { buildMap { for(track in tracks)for(observation in track.observations)put(observation.identity(),track.id) } }
    init { require(tracks.size <= 15000 && tracks.sumOf { it.observations.size } <= 1_000_000)
        require(tracks.sumOf { track -> track.observations.sumOf { it.photo.toByteArray(Charsets.UTF_8).size.toLong()+8 } } <= 60_000_000)
        require(tracks.map { it.id }.distinct().size == tracks.size)
        require(tracks.flatMap { it.observations }.distinct().size == tracks.sumOf { it.observations.size }) }
    fun resolve(oldIdentity: String): String? = aliases[oldIdentity]
    fun encode(): ByteArray {
        val payload=ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x52465453);out.writeInt(1);out.writeInt(tracks.size)
            for(t in tracks.sortedBy { it.id }) { out.writeInt(t.observations.size)
                for(o in t.observations){ val p=o.photo.toByteArray(Charsets.UTF_8);out.writeInt(p.size);out.write(p);out.writeInt(o.keypoint) }
            }
        }}.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    companion object {
        fun decode(bytes: ByteArray): TrackSnapshot {
            require(bytes.size in 44..64_000_000)
            val payload=bytes.copyOfRange(0,bytes.size-32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload),bytes.copyOfRange(bytes.size-32,bytes.size)))
            val input=DataInputStream(ByteArrayInputStream(payload))
            require(input.readInt()==0x52465453 && input.readInt()==1)
            val n=input.readInt();require(n in 0..15000);var total=0
            val tracks=List(n){ val count=input.readInt();require(count in 2..250);total+=count;require(total<=1_000_000)
                val observations=List(count){val size=input.readInt();require(size in 1..1024 && size<=input.available()-4)
                    val p=ByteArray(size);input.readFully(p);val photo=p.toString(Charsets.UTF_8)
                    require(photo.toByteArray(Charsets.UTF_8).contentEquals(p));SourceObservation(photo,input.readInt()) }
                StableTrack(observations.minOf { it.identity() }, observations)
            }
            require(input.available()==0)
            return TrackSnapshot(tracks)
        }
    }
}
/** Compact indices are valid only with these explicit local-to-global mappings. */
internal class NativeTrackBatch(sourcePhotoIds: List<String>, sourceTrackIds: List<String>, sourceRows: DoubleArray) {
    val photoIds: List<String> = Collections.unmodifiableList(sourcePhotoIds.toList())
    val trackIds: List<String> = Collections.unmodifiableList(sourceTrackIds.toList())
    private val values = sourceRows.copyOf()
    val rows: DoubleArray get() = values.copyOf()
    init { require(photoIds.size in 2..12 && photoIds.distinct().size==photoIds.size && trackIds.size in 0..15000 && trackIds.distinct().size==trackIds.size)
        require(photoIds.all {it.isNotBlank() && it.toByteArray().size<=1024} && trackIds.all {it.matches(Regex("[0-9a-f]{64}"))})
        require(values.size%7==0 && values.size/7 in 0..100000 && values.all {it.isFinite()})
        require(trackIds.isEmpty()==values.isEmpty())
        val support=Array(trackIds.size){mutableSetOf<Int>()}
        for(i in values.indices step 7){require(values[i]>=0 && values[i]<trackIds.size && values[i]%1.0==0.0)
            require(values[i+1]>=0 && values[i+1]<photoIds.size && values[i+1]%1.0==0.0)
            require(kotlin.math.abs(values[i+2])<=10 && kotlin.math.abs(values[i+3])<=10)
            require((4..6).all {values[i+it] in 0.0..255.0})
            require(support[values[i].toInt()].add(values[i+1].toInt())) }
        require(support.all {it.size>=2})
    }
}
