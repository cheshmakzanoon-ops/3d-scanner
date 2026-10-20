package com.realityforge.scanner.engine.registration

import com.realityforge.scanner.engine.tracks.TrackSnapshot
import java.io.*
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs

internal class FrontierCamera(val photoId:String,sourceRotation:List<Double>,sourceTranslation:List<Double>){
 val rotation:List<Double> = Collections.unmodifiableList(sourceRotation.toList())
 val translation:List<Double> = Collections.unmodifiableList(sourceTranslation.toList())
 init {require(photoId.isNotBlank() && photoId.toByteArray().size<=1024 && rotation.size==9 && translation.size==3)
  require(rotation.all {it.isFinite()} && translation.all {it.isFinite()&&abs(it)<100000})
  for(a in 0..2)for(b in 0..2)require(abs((0..2).sumOf {rotation[a*3+it]*rotation[b*3+it]}-if(a==b)1.0 else 0.0)<.01)
  val r=rotation;require(abs(r[0]*(r[4]*r[8]-r[5]*r[7])-r[1]*(r[3]*r[8]-r[5]*r[6])+r[2]*(r[3]*r[7]-r[4]*r[6])-1)<.01)
 }
}
/** A checksummed stage checkpoint, never a published completed reconstruction. */
internal class RegistrationFrontier(val inputIdentity:String,sourcePhotoIds:List<String>,sourceCameras:List<FrontierCamera>,sourceFailureReasons:Map<String,String>, sourceTrackSnapshot:ByteArray=ByteArray(0)){
 val photoIds:List<String> = Collections.unmodifiableList(sourcePhotoIds.toList())
 val cameras:List<FrontierCamera> = Collections.unmodifiableList(sourceCameras.toList())
 val failureReasons:Map<String,String> = Collections.unmodifiableMap(sourceFailureReasons.toMap())
 private val sourceTracks=sourceTrackSnapshot.copyOf()
 val trackSnapshot:ByteArray get()=sourceTracks.copyOf()
 init {require(inputIdentity.matches(Regex("[a-f0-9]{64}")))
  require(photoIds.size in 2..250 && photoIds.distinct().size==photoIds.size && photoIds.all {it.isNotBlank()&&it.toByteArray().size<=1024})
  require(cameras.size in 2..photoIds.size && cameras.map {it.photoId}.distinct().size==cameras.size && cameras.all {it.photoId in photoIds})
  require(failureReasons.size<=250 && failureReasons.all {it.key in photoIds&&it.value.toByteArray().size<=2048})
  require(sourceTracks.size<=12_000_000)
  if(sourceTracks.isNotEmpty()){val snapshot=TrackSnapshot.decode(sourceTracks);require(snapshot.tracks.all {t->t.observations.all {o->o.photo in cameras.map {it.photoId}}})}
 }
 fun encode():ByteArray{val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use {o->
  o.writeInt(0x52465246);o.writeInt(2);o.writeUTF(inputIdentity);o.writeInt(photoIds.size);photoIds.forEach {o.writeUTF(it)}
  o.writeInt(cameras.size);for(c in cameras){o.writeUTF(c.photoId);c.rotation.forEach {o.writeDouble(it)};c.translation.forEach {o.writeDouble(it)}}
  o.writeInt(failureReasons.size);for((id,reason) in failureReasons.toSortedMap()){o.writeUTF(id);o.writeUTF(reason)};o.writeInt(sourceTracks.size);o.write(sourceTracks) }
  val payload=bytes.toByteArray();return payload+MessageDigest.getInstance("SHA-256").digest(payload) }
 companion object {
  fun decode(bytes:ByteArray):RegistrationFrontier{require(bytes.size in 64..13_000_000);val payload=bytes.copyOfRange(0,bytes.size-32)
   require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload),bytes.copyOfRange(bytes.size-32,bytes.size)))
   val i=DataInputStream(ByteArrayInputStream(payload));require(i.readInt()==0x52465246&&i.readInt()==2);val identity=i.readUTF();val n=i.readInt();require(n in 2..250)
   val ids=List(n){i.readUTF()};val c=i.readInt();require(c in 2..n);val cameras=List(c){FrontierCamera(i.readUTF(),List(9){i.readDouble()},List(3){i.readDouble()})}
   val f=i.readInt();require(f in 0..250);val failures=linkedMapOf<String,String>();repeat(f){val id=i.readUTF();require(id !in failures);failures[id]=i.readUTF()};val size=i.readInt();require(size in 0..12_000_000 && size==i.available());val tracks=ByteArray(size);i.readFully(tracks);require(i.available()==0)
   return RegistrationFrontier(identity,ids,cameras,failures,tracks) }
 }
}
