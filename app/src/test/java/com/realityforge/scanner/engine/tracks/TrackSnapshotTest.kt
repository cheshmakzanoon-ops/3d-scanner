package com.realityforge.scanner.engine.tracks
import org.junit.Assert.*
import org.junit.Test
class TrackSnapshotTest {
 private fun track(vararg ids:String):StableTrack {val o=ids.map { SourceObservation(it,7) }.sorted();return StableTrack(o.minOf { it.identity() },o)}
 @Test fun roundTripAndAliases(){val a=track("photo-z","photo-a");val s=TrackSnapshot(listOf(a));assertEquals(s,TrackSnapshot.decode(s.encode()));assertEquals(a.id,s.resolve(SourceObservation("photo-z",7).identity()))}
 @Test fun rejectsTampering(){val b=TrackSnapshot(listOf(track("a","b"))).encode();b[18]=(b[18].toInt() xor 1).toByte();assertThrows(IllegalArgumentException::class.java){TrackSnapshot.decode(b)}}
 @Test fun boundedLargeCollection(){val s=TrackSnapshot((0 until 125).map {track("photo-${it*2}","photo-${it*2+1}")});assertEquals(125,TrackSnapshot.decode(s.encode()).tracks.size);assertTrue(s.encode().size<25000)}
 @Test fun samePhotoConflictIsInvalid(){assertThrows(IllegalArgumentException::class.java){val o=listOf(SourceObservation("a",1),SourceObservation("a",2));StableTrack(o.minOf {it.identity()},o)}}
 @Test fun rejectsAppendedBytesAndUnknownVersion(){val bytes=TrackSnapshot(listOf(track("a","b"))).encode();assertThrows(IllegalArgumentException::class.java){TrackSnapshot.decode(bytes+byteArrayOf(0))}
  val payload=bytes.copyOfRange(0,bytes.size-32);payload[7]=2;val checksum=java.security.MessageDigest.getInstance("SHA-256").digest(payload)
  assertThrows(IllegalArgumentException::class.java){TrackSnapshot.decode(payload+checksum)}}
}
