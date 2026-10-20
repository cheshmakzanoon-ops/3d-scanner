package com.realityforge.scanner.engine.tracks

import java.io.*
import java.security.MessageDigest

/** Durable identities belong to source photographs, never the current native array order. */
internal data class SourceObservation(val photo: String, val keypoint: Int) : Comparable<SourceObservation> {
    init { require(photo.isNotBlank() && '\u0000' !in photo && photo.toByteArray(Charsets.UTF_8).size <= 1024 && photo.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==photo && keypoint in 0..1_000_000) }
    override fun compareTo(other: SourceObservation) = compareValuesBy(this, other, { it.photo }, { it.keypoint })
    fun identity(): String = digest("observation-v1\u0000$photo\u0000$keypoint".toByteArray(Charsets.UTF_8))
}
internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal data class StableTrack(val id: String, val observations: List<SourceObservation>) {
    init {
        require(observations.size in 2..250 && observations == observations.sorted() && observations.distinct().size == observations.size)
        require(observations.map { it.photo }.distinct().size == observations.size)
        require(id == observations.minOf { it.identity() })
    }
}
/** Every source-observation identity aliases the current canonical track. A merge can
 * change the canonical minimum, but old published identities resolve without recycling. */
internal data class TrackSnapshot(val tracks: List<StableTrack>) {
    init { require(tracks.size <= 15000 && tracks.sumOf { it.observations.size } <= 1_000_000)
        require(tracks.sumOf { track -> track.observations.sumOf { it.photo.toByteArray(Charsets.UTF_8).size.toLong()+8 } } <= 60_000_000)
        require(tracks.map { it.id }.distinct().size == tracks.size)
        require(tracks.flatMap { it.observations }.distinct().size == tracks.sumOf { it.observations.size }) }
    fun resolve(oldIdentity: String): String? = tracks.firstOrNull { t -> t.observations.any { it.identity() == oldIdentity } }?.id
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
internal data class NativeTrackBatch(val photoIds: List<String>, val trackIds: List<String>, val rows: DoubleArray)
