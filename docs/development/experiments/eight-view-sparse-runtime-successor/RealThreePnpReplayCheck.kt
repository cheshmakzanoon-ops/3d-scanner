package com.realityforge.scanner.hostintegration

import com.realityforge.scanner.engine.*
import com.realityforge.scanner.engine.features.*
import com.realityforge.scanner.engine.registration.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

/** Synthetic controls of real Kotlin replay path. NOT photographic or JNI qualification. */
class RealThreePnpReplayCheck {
    private val stage="a".repeat(64);private val matching="b".repeat(64)
    private val reason="Insufficient consistent 2D-3D tracks [pnp1 raw=20 distinct=20 required=16 best=1 tested=450 valid=450 model=dlt planarity=0.064929]"
    private class Data(val root:File,val photos:List<RealThreePnpReplay.Photo>,val views:List<SparseView>,val cameras:List<FrontierCamera>,val xyz:List<DoubleArray>)
    private fun fixture(duplicateGeometry:Boolean=false):Data {
        val root=Files.createTempDirectory("pnp-replay-control").toFile().canonicalFile
        File(root,"features").mkdir();File(root,"sparse-frontier-checkpoints-v1").mkdir()
        val focal=88.0*hypot(1600.0,1067.0)/hypot(36.0,24.0)
        val lens=LensProjection.estimated(1600,1067,focal)
        val xyz=MutableList(20){i->doubleArrayOf((i-10)*.03,(i%4)*.03,5.0+(i%3)*.2)}
        if(duplicateGeometry)xyz[1]=xyz[0].copyOf()
        val photos=(0..2).map{i->RealThreePnpReplay.Photo("p$i",FeatureCacheIdentity(RealThreePnpReplay.ORIGINALS[i],"decoder","extractor","4.12.0"))}
        val views=photos.mapIndexed{i,p->
            val xy=FloatArray(40){j->val point=xyz[j/2];if(j%2==0)(lens.cx+(point[0]-i*.2)/point[2]*focal).toFloat() else (lens.cy+point[1]/point[2]*focal).toFloat()}
            val colors=IntArray(20){123};val r=CachedFeatures(p.identity,1600,1067,xy,colors,FloatArray(20*128))
            File(root,"features/"+RealThreePnpReplay.featureName(p.identity)).writeBytes(FeatureCacheFormat.encode(r))
            SparseView(1600,1067,focal,xy,colors,doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),DoubleArray(3),lens)
        }
        val cameras=(0..1).map{i->FrontierCamera(photos[i].id,views[i].rotation.toList(),listOf(-i*.2,0.0,0.0))}
        val frontier=RegistrationFrontier(IncrementalRegistration.inputIdentity(views,photos.map{it.id}),photos.map{it.id},cameras,mapOf("p2" to reason))
        val payload=frontier.encode();val envelope=ByteBuffer.allocate(112+payload.size).put("RFFRNT01".toByteArray()).putInt(1).put(stage.toByteArray()).putInt(payload.size).put(MessageDigest.getInstance("SHA-256").digest(payload)).put(payload).array()
        File(root,"sparse-frontier-checkpoints-v1/$stage.frontier").writeBytes(envelope)
        val match=ByteArrayOutputStream().also{stream->DataOutputStream(stream).use{out->
            out.writeInt(0x52464d33);out.writeUTF(matching);out.writeInt(3);out.writeInt(3)
            for((a,b) in listOf(0 to 1,0 to 2,1 to 2)) {
                out.writeInt(a);out.writeInt(b);out.writeInt(20)
                for(i in 0 until 20){out.writeInt(i);out.writeInt(i);val x=views[a].normalized(i);val y=views[b].normalized(i)
                    for(d in listOf(x.first,x.second,y.first,y.second,0.0,0.0,123.0))out.writeDouble(d)}
            }
        }}.toByteArray()
        File(root,"matches-$matching.rfmg").writeBytes(match);File(root,"matches-$matching.rfmg.sha256").writeText(RealThreePnpReplay.sha(match))
        return Data(root,photos,views,cameras,xyz)
    }
    private fun native(d:Data,mutate:(()->Unit)?=null)=NativeFrontierCallbacks(
        {poses,rows,_->assertEquals(24,poses.size);assertEquals(20*2*7,rows.size)
            List(20){FrontierPoint(it,d.xyz[it].copyOf(),listOf(0,1))}},
        {rows,_->assertEquals(100,rows.size)
            for(i in 0 until 20){assertArrayEquals(d.xyz[i],rows.copyOfRange(i*5,i*5+3),0.0)
                val uv=d.views[2].normalized(i);assertEquals(uv.first,rows[i*5+3],0.0);assertEquals(uv.second,rows[i*5+4],0.0)}
            mutate?.invoke();throw IllegalArgumentException(reason)})
    private suspend fun run(d:Data,n:NativeFrontierCallbacks=native(d),count:Int=20,error:String=reason)=
        RealThreePnpReplay.replay(d.root,d.photos,matching,stage,count,error,"p2",d.cameras,n)
    @Test fun actualGrowthSyntheticCallbacksRetainRowsAndNeverMutateCache()=runBlocking {
        val d=fixture()
        try {
            val files=d.root.walkTopDown().filter{it.isFile}.toList();val bytes=files.associateWith{it.readBytes()};val times=files.associateWith{it.lastModified()}
            val out=run(d);assertEquals("VERIFIED_REPLAY",out.getString("status"));assertEquals(20,out.getInt("count"));assertEquals(12,out.getInt("candidateLabel"))
            assertEquals(20,out.getJSONArray("rows").length());assertEquals(6,out.getJSONObject("cacheHashes").length())
            for(f in files){assertArrayEquals(bytes.getValue(f),f.readBytes());assertEquals(times.getValue(f),f.lastModified())}
            System.getProperty("replay.control.output")?.let{File(it).writeText(out.toString())}
            Unit
        }finally{d.root.deleteRecursively()}
    }
    @Test fun missingOrChangedCacheAndChecksumRefuseWithoutFallback()=runBlocking {
        for(mode in 0..2){val d=fixture();try {
            val f=File(d.root,"matches-$matching.rfmg")
            if(mode==0)f.delete() else if(mode==1)f.appendBytes(byteArrayOf(0)) else File(d.root,"matches-$matching.rfmg.sha256").writeText("0".repeat(64))
            try{run(d);fail("Invalid cache accepted")}catch(expected:Exception){}
        }finally{d.root.deleteRecursively()}}
    }
    @Test fun callbackCountReasonOrPersistedPoseMismatchRefuses()=runBlocking {
        val d=fixture();try {
            for(mode in 0..2){
                try {
                    when(mode){0->run(d,count=21);1->run(d,error=reason.replace("best=1","best=2"));else->
                        RealThreePnpReplay.replay(d.root,d.photos,matching,stage,20,reason,"p2",d.cameras.reversed(),native(d))}
                    fail("Mismatched replay accepted")
                }catch(expected:IllegalArgumentException){}
            }
        }finally{d.root.deleteRecursively()}
    }
    @Test fun postCallbackMutationRefusesSuccess()=runBlocking {
        val d=fixture();try {
            try{run(d,native(d){File(d.root,"matches-$matching.rfmg").appendBytes(byteArrayOf(0))});fail("Mutation accepted")}
            catch(expected:IllegalArgumentException){}
        }finally{d.root.deleteRecursively()}
    }
    @Test fun pureEnvelopeRefusesTruncationForeignIdentityAndHashTamper() {
        val d=fixture();try {
            val bytes=File(d.root,"sparse-frontier-checkpoints-v1/$stage.frontier").readBytes()
            assertEquals(2,RealThreePnpReplay.frontier(bytes,stage).cameras.size)
            for(input in listOf(bytes.copyOf(111),bytes+byteArrayOf(0),bytes.copyOf().also{it[it.lastIndex]=(it.last()+1).toByte()}))
                assertThrows(IllegalArgumentException::class.java){RealThreePnpReplay.frontier(input,stage)}
            assertThrows(IllegalArgumentException::class.java){RealThreePnpReplay.frontier(bytes,"c".repeat(64))}
            val matches=File(d.root,"matches-$matching.rfmg").readBytes()
            assertThrows(IllegalArgumentException::class.java){RealThreePnpReplay.matches(matches,"c".repeat(64))}
            assertThrows(IllegalArgumentException::class.java){RealThreePnpReplay.matches(matches+byteArrayOf(0),matching)}
        }finally{d.root.deleteRecursively()}
    }
    @Test fun symlinksAndNonfiniteRowsRefuse() {
        val d=fixture();try {
            val link=File(d.root,"alias");Files.createSymbolicLink(link.toPath(),File(d.root,"matches-$matching.rfmg").toPath())
            assertThrows(IllegalArgumentException::class.java){RealThreePnpReplay.StableRead(d.root).read("alias",link,100000)}
            val bytes=File(d.root,"matches-$matching.rfmg").readBytes()
            // Header 4+UTF(2+64)+photo/count8+pair header12+keys8 -> first double.
            ByteBuffer.wrap(bytes).putDouble(98,Double.NaN)
            assertThrows(IllegalArgumentException::class.java){RealThreePnpReplay.matches(bytes,matching)}
        }finally{d.root.deleteRecursively()}
    }
    @Test fun cancellationAndForeignCandidateCannotBecomeReplay()=runBlocking {
        val d=fixture();try {
            var registered=false
            try {run(d,NativeFrontierCallbacks({_,_,_->throw java.util.concurrent.CancellationException("control")},
                {_,_->registered=true;error("No register after cancellation")}));fail("Cancellation swallowed")}
            catch(expected:java.util.concurrent.CancellationException){}
            assertFalse(registered)
            try {RealThreePnpReplay.replay(d.root,d.photos,matching,stage,20,reason,"foreign",d.cameras,native(d));fail("Wrong candidate accepted")}
            catch(expected:IllegalArgumentException){}
        }finally{d.root.deleteRecursively()}
    }
    @Test fun replacedRootDirectoryIdentityRefuses() {
        val root=Files.createTempDirectory("replay-root-identity").toFile().canonicalFile
        val moved=File(root.parentFile,root.name+"-moved")
        try {
            val reader=RealThreePnpReplay.StableRead(root)
            Files.move(root.toPath(),moved.toPath());Files.createDirectory(root.toPath())
            assertThrows(IllegalArgumentException::class.java){reader.verify()}
        }finally{root.deleteRecursively();moved.deleteRecursively()}
    }
    @Test fun rehashedOutOfRangeSourceKeyStillRefuses()=runBlocking {
        val d=fixture();try {
            val file=File(d.root,"matches-$matching.rfmg");val bytes=file.readBytes()
            ByteBuffer.wrap(bytes).putInt(90,3999);file.writeBytes(bytes)
            File(d.root,"matches-$matching.rfmg.sha256").writeText(RealThreePnpReplay.sha(bytes))
            try{run(d);fail("Foreign source key accepted")}catch(expected:IllegalArgumentException){}
        }finally{d.root.deleteRecursively()}
    }

    @Test fun geometricDuplicateOriginsAreExplicitlyAmbiguousNotInventedUnique()=runBlocking {
        val d=fixture(duplicateGeometry=true)
        try {
            val changed=reason.replace("raw=20 distinct=20","raw=19 distinct=19")
            val callbacks=NativeFrontierCallbacks({_,_,_->List(20){FrontierPoint(it,d.xyz[it].copyOf(),listOf(0,1))}},
                {rows,_->assertEquals(95,rows.size);throw IllegalArgumentException(changed)})
            val result=run(d,callbacks,19,changed)
            assertEquals(19,result.getInt("count"))
            val origin=result.getJSONArray("provenance").getJSONObject(0)
            assertTrue(origin.getBoolean("ambiguous"));assertEquals(2,origin.getJSONArray("tracks").length())
            assertEquals(2,result.getJSONArray("anchorPoses").length());assertEquals(3,result.getJSONArray("projections").length())
        }finally{d.root.deleteRecursively()}
    }

}
