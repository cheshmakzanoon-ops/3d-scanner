package com.realityforge.scanner.hostintegration

import com.realityforge.scanner.engine.*
import com.realityforge.scanner.engine.features.*
import com.realityforge.scanner.engine.pipeline.FeatureMatchRecord
import com.realityforge.scanner.engine.registration.RegistrationFrontier
import com.realityforge.scanner.engine.registration.FrontierCamera
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** Fixed licensed fixture diagnostic ONLY. Reads existing intermediates; never loads photos,
 * touches LRU mtimes, writes checkpoints, publishes poses or converts strict failure to PASS. */
internal object RealThreePnpReplay {
    const val CLASSIFICATION="READ_ONLY_REPLAY_NOT_ORIGINAL_CALL_NOT_QUALITY"
    val ORIGINALS=listOf(
        "9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e",
        "dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c",
        "f771973ad8f3092d6e869d29c4cb88a0ad67d30c6cf03dfe9dce70c98cdd9bb1")
    data class Photo(val id:String,val identity:FeatureCacheIdentity)
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun digest(value:String)=value.matches(Regex("[a-f0-9]{64}"))
    fun failure()=JSONObject().put("schema",1).put("classification",CLASSIFICATION)
        .put("status","DIAGNOSTIC_FAILURE").put("code","READ_ONLY_REPLAY_UNAVAILABLE")
    internal fun featureName(identity:FeatureCacheIdentity):String {
        val text=listOf(identity.originalSha256,identity.decoderTransform,identity.extractorSettings,identity.openCvVersion)
            .joinToString(""){"${it.toByteArray(Charsets.UTF_8).size}:$it"}
        return sha(text.toByteArray(Charsets.UTF_8))+".rffc"
    }
    /** Version-bound TEST decoder; production file loader is deliberately not invoked. */
    internal fun matches(bytes:ByteArray,identity:String):Map<Pair<Int,Int>,FeatureMatchRecord> {
        require(digest(identity) && bytes.size in 24..(128+3*(12+4000*64)))
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt()==0x52464d33 && input.readUTF()==identity && input.readInt()==3)
            require(input.readInt()==3)
            val result=linkedMapOf<Pair<Int,Int>,FeatureMatchRecord>()
            repeat(3) {
                val a=input.readInt();val b=input.readInt();val count=input.readInt()
                require(a in 0..2 && b in a+1..2 && count in 0..3500)
                val first=IntArray(count);val second=IntArray(count);val rows=DoubleArray(count*7)
                repeat(count) { i ->first[i]=input.readInt();second[i]=input.readInt()
                    repeat(7){j->rows[i*7+j]=input.readDouble()} }
                require(result.put(a to b,FeatureMatchRecord(rows,first,second))==null)
            }
            require(input.read()==-1 && result.keys==setOf(0 to 1,0 to 2,1 to 2));result
        }
    }
    internal fun frontier(bytes:ByteArray,stage:String):RegistrationFrontier {
        require(digest(stage) && bytes.size in 113..1_000_000)
        val b=ByteBuffer.wrap(bytes);val magic=ByteArray(8).also{b.get(it)}
        require(magic.contentEquals("RFFRNT01".toByteArray()) && b.int==1)
        val identity=ByteArray(64).also{b.get(it)}.toString(Charsets.US_ASCII)
        val count=b.int;val hash=ByteArray(32).also{b.get(it)}
        require(identity==stage && count==bytes.size-112 && count>0)
        val payload=ByteArray(count).also{b.get(it)}
        require(MessageDigest.isEqual(hash,MessageDigest.getInstance("SHA-256").digest(payload)))
        return RegistrationFrontier.decode(payload)
    }
    internal class StableRead(private val root:File) {
        private data class Entry(val file:File,val bytes:ByteArray,val attrs:BasicFileAttributes)
        private val entries=linkedMapOf<String,Entry>()
        private val rootAttrs=Files.readAttributes(root.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        init {require(root.isDirectory && rootAttrs.fileKey()!=null && root.canonicalFile==root.absoluteFile.toPath().normalize().toFile())}
        fun read(name:String,file:File,maximum:Int):ByteArray {
            require(name !in entries && entries.size<6 && maximum in 1..12*1024*1024)
            require(file.toPath().startsWith(root.toPath()) && file.canonicalFile==file.absoluteFile.toPath().normalize().toFile())
            val a=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            require(a.isRegularFile && !a.isSymbolicLink && a.fileKey()!=null && a.size() in 1..maximum.toLong())
            val bytes=Files.newInputStream(file.toPath(),NOFOLLOW_LINKS).use{it.readNBytes(maximum+1)}
            require(bytes.size.toLong()==a.size() && bytes.size<=maximum)
            entries[name]=Entry(file,bytes,a);verify();return bytes
        }
        fun verify() {
            require(root.canonicalFile==root.absoluteFile.toPath().normalize().toFile())
            val currentRoot=Files.readAttributes(root.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            require(currentRoot.isDirectory && !currentRoot.isSymbolicLink && currentRoot.fileKey()==rootAttrs.fileKey() &&
                currentRoot.creationTime()==rootAttrs.creationTime() && currentRoot.lastModifiedTime()==rootAttrs.lastModifiedTime())
            for(e in entries.values) {
            require(e.file.canonicalFile==e.file.absoluteFile.toPath().normalize().toFile())
            val a=Files.readAttributes(e.file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            require(a.isRegularFile && !a.isSymbolicLink && a.size()==e.attrs.size() &&
                a.lastModifiedTime()==e.attrs.lastModifiedTime() && a.creationTime()==e.attrs.creationTime() && a.fileKey()==e.attrs.fileKey())
            val bytes=Files.newInputStream(e.file.toPath(),NOFOLLOW_LINKS).use{it.readNBytes(e.bytes.size+1)}
            require(bytes.contentEquals(e.bytes))
        }}
        fun hashes()=JSONObject().also { out->entries.forEach{(name,e)->out.put(name,sha(e.bytes))} }
    }
    private fun numericReason(reason:String):String {
        require(reason.length<=200)
        val match=Regex("\\[pnp1 raw=([0-9]+) distinct=([0-9]+) required=([0-9]+) best=([0-9]+) tested=([0-9]+) valid=([0-9]+) model=(dlt|plane|none)(?: planarity=([0-9]+\\.[0-9]{6}))?\\]")
            .find(reason) ?: error("Missing numeric PnP failure")
        require(reason.length<=200)
        val raw=match.groupValues[1].toInt();val distinct=match.groupValues[2].toInt()
        val required=match.groupValues[3].toInt();val best=match.groupValues[4].toInt()
        val tested=match.groupValues[5].toInt();val valid=match.groupValues[6].toInt()
        require(raw in 16..128 && distinct==raw && required==maxOf(16,kotlin.math.ceil(raw*.28).toInt()))
        require(best in 0 until required && tested in 0..450 && valid in 0..tested)
        val model=match.groupValues[7];val ratio=match.groupValues[8]
        require(if(model=="none")ratio.isEmpty() else ratio.isNotEmpty() && ratio.toDouble() in 0.0..1.0)
        return match.value
    }
    internal fun rowHash(rows:DoubleArray)=sha(ByteBuffer.allocate(rows.size*8).also{b->rows.forEach{b.putDouble(it)}}.array())

    suspend fun replay(root:File,photos:List<Photo>,matchingStage:String,sparseStage:String,
        expectedRows:Int,expectedReason:String,expectedCandidateId:String,expectedCameras:List<FrontierCamera>,native:NativeFrontierCallbacks):JSONObject {
        require(photos.size==3 && photos.map{it.id}.distinct().size==3 && photos.map{it.identity.originalSha256}.toSet()==ORIGINALS.toSet())
        require(expectedRows in 16..128 && digest(matchingStage) && digest(sparseStage))
        val expectedNumeric=numericReason(expectedReason)
        require(expectedNumeric.startsWith("[pnp1 raw=$expectedRows "))
        val context=currentCoroutineContext();context.ensureActive()
        val read=StableRead(root)
        try {
            val records=photos.mapIndexed { i,p->
                context.ensureActive()
                val bytes=read.read("feature${ORIGINALS.indexOf(p.identity.originalSha256)+10}",File(root,"features/"+featureName(p.identity)),FeatureCacheFormat.MAX_BYTES)
                FeatureCacheFormat.decode(bytes,p.identity).also{require(it.count in 16..3500 && it.width in 32..1600 && it.height in 32..1600)}
            }
            val templates=records.map { r->
                val focal=88.0*hypot(r.width.toDouble(),r.height.toDouble())/hypot(36.0,24.0)
                SparseView(r.width,r.height,focal,r.coordinates(),r.colors(),
                    doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),DoubleArray(3),
                    LensProjection.estimated(r.width,r.height,focal))
            }
            val matchBytes=read.read("matches",File(root,"matches-$matchingStage.rfmg"),128+3*(12+4000*64))
            val checksum=read.read("matchesChecksum",File(root,"matches-$matchingStage.rfmg.sha256"),64)
            require(checksum.toString(Charsets.US_ASCII)==sha(matchBytes))
            val links=matches(matchBytes,matchingStage)
            for((pair,m) in links)for(i in m.firstKeys.indices) {
                require(m.firstKeys[i] in templates[pair.first].colors.indices && m.secondKeys[i] in templates[pair.second].colors.indices)
                val a=templates[pair.first].normalized(m.firstKeys[i]);val b=templates[pair.second].normalized(m.secondKeys[i])
                require(m.rows[i*7]==a.first && m.rows[i*7+1]==a.second && m.rows[i*7+2]==b.first && m.rows[i*7+3]==b.second)
            }
            val saved=frontier(read.read("frontier",File(root,"sparse-frontier-checkpoints-v1/$sparseStage.frontier"),1_000_000),sparseStage)
            val ids=photos.map{it.id};require(saved.photoIds==ids && saved.inputIdentity==IncrementalRegistration.inputIdentity(templates,ids))
            require(saved.cameras.size==2 && saved.cameras.map{it.photoId}.distinct().size==2 && expectedCameras.size==2)
            require(saved.cameras.indices.all{i->val a=saved.cameras[i];val b=expectedCameras[i]
                a.photoId==b.photoId && a.rotation==b.rotation && a.translation==b.translation})
            val anchors=saved.cameras.map { c->val i=ids.indexOf(c.photoId);require(i>=0)
                RegisteredFrame(i,templates[i].copy(rotation=c.rotation.toDoubleArray(),translation=c.translation.toDoubleArray())) }
            fun pair(a:Int,b:Int):FeaturePairs {
                val m=links.getValue(minOf(a,b) to maxOf(a,b));val p=FeaturePairs(m.firstKeys,m.secondKeys)
                return if(a<b)p else p.reversed()
            }
            // Independent provenance reconstruction; exact callback-input agreement is mandatory.
            val graph=MultiViewTrackGraph(anchors.map{it.view})
            val anchorLinks=pair(anchors[0].photo,anchors[1].photo)
            for(i in anchorLinks.first.indices)graph.add(0,anchorLinks.first[i],1,anchorLinks.second[i])
            val groups=graph.snapshot();val sourceRows=graph.packedRows()
            var returnedPoints=emptyList<FrontierPoint>();var sourceGate=0.0
            var triangulations=0;var registrations=0;var captured=DoubleArray(0);var gate=0.0;var actualReason=""
            var triangulationHash="";var poseHash="";var pointsHash=""
            val growth=IncrementalRegistration.grow(templates,anchors,
                {a,b->pair(a,b)},
                {poses,observations,threshold->
                    context.ensureActive();require(++triangulations==1 && poses.size==24 && observations.size<=7000*7)
                    require(observations.contentEquals(sourceRows))
                    sourceGate=threshold;poseHash=rowHash(poses);triangulationHash=rowHash(observations)
                    native.triangulate(poses,observations,threshold).also { points->
                        require(points.size<=3500)
                        returnedPoints=points.map{it.copy(xyz=it.xyz.copyOf(),supportingCameras=it.supportingCameras?.toList())}
                        pointsHash=rowHash(points.flatMap{listOf(it.track.toDouble(),*it.xyz.toTypedArray())}.toDoubleArray())
                    }
                },
                {rows,threshold->
                    context.ensureActive();require(++registrations==1 && rows.size==expectedRows*5 && rows.all{it.isFinite()})
                    require(rows.indices.all{if(it%5<3)kotlin.math.abs(rows[it])<=100000 else kotlin.math.abs(rows[it])<=10})
                    captured=rows.copyOf();gate=threshold
                    try {native.register(rows,threshold)} catch(e:IllegalArgumentException) {actualReason=numericReason(e.message.orEmpty());throw e}
                },photoIds=ids,resume=saved,checkpoint={},neighbors={i->ids.indices.filter{it!=i}.toSet()},pairBudget=3)
            context.ensureActive();require(triangulations==1 && registrations==1 && actualReason==expectedNumeric)
            require(growth.cameras.size==2 && growth.attempts.size==1 && !growth.attempts.single().accepted && growth.attempts.single().tracks==expectedRows)
            val candidate=growth.attempts.single().photo
            require(ids[candidate]==expectedCandidateId && growth.attempts.single().registeredBasis==2)
            require(gate==2.0/templates[candidate].focalPixels)
            val targetLinks=anchors.map { anchor->val p=pair(anchor.photo,candidate)
                p.first.indices.associate{p.first[it] to p.second[it]} }
            data class Origin(val row:DoubleArray,val key:Int,val track:Int,val sources:JSONArray)
            val possible=returnedPoints.mapNotNull { point->
                require(point.track in groups.indices)
                val group=groups[point.track]
                if(group.size!=2)return@mapNotNull null
                val source=JSONArray();val destinations=mutableListOf<Int>()
                for((local,key) in group) {
                    if(point.supportingCameras!=null && local !in point.supportingCameras)return@mapNotNull null
                    val camera=anchors[local];val view=camera.view;val uv=view.normalized(key)
                    val projected=DoubleArray(3){axis->(0..2).sumOf{k->view.rotation[axis*3+k]*point.xyz[k]}+view.translation[axis]}
                    if(projected[2]<=1e-8 || hypot(projected[0]/projected[2]-uv.first,projected[1]/projected[2]-uv.second)>sourceGate*2)return@mapNotNull null
                    val linked=targetLinks[local][key] ?: -1
                    if(linked>=0)destinations.add(linked)
                    source.put(JSONArray(listOf(ORIGINALS.indexOf(photos[camera.photo].identity.originalSha256)+10,key,uv.first,uv.second,linked)))
                }
                if(destinations.isEmpty() || destinations.distinct().size!=1)return@mapNotNull null
                val key=destinations.first();val uv=templates[candidate].normalized(key)
                Origin(doubleArrayOf(*point.xyz,uv.first,uv.second),key,point.track,source)
            }
            val frequencies=possible.groupingBy{it.key}.eachCount()
            val eligible=possible.filter{frequencies[it.key]==1}
            val provenance=JSONArray((0 until expectedRows).map { i->
                val origins=eligible.filter{o->(0 until 5).all{j->o.row[j]==captured[i*5+j]}}
                require(origins.size in 1..8)
                JSONObject().put("ambiguous",origins.size>1).put("tracks",JSONArray(origins.map{o->
                    JSONObject().put("track",o.track).put("candidateKey",o.key).put("sources",o.sources)}))
            })
            val projections=JSONArray(templates.mapIndexed { i,v->val k=requireNotNull(v.projection)
                JSONArray(listOf(ORIGINALS.indexOf(photos[i].identity.originalSha256)+10,v.width,v.height,k.fx,k.fy,k.cx,k.cy,k.k1,k.k2,k.p1,k.p2,k.k3)) })
            val poses=JSONArray(anchors.map { camera->JSONArray(listOf(ORIGINALS.indexOf(photos[camera.photo].identity.originalSha256)+10)+camera.view.rotation.toList()+camera.view.translation.toList()) })
            read.verify()
            return JSONObject().put("schema",1).put("classification",CLASSIFICATION).put("status","VERIFIED_REPLAY")
                .put("originalHashes",JSONArray(ORIGINALS)).put("cacheHashes",read.hashes())
                .put("matchingStage",matchingStage).put("sparseStage",sparseStage)
                .put("candidateLabel",ORIGINALS.indexOf(photos[candidate].identity.originalSha256)+10).put("count",expectedRows).put("threshold",gate)
                .put("numericFailure",actualReason).put("rowsSha256",rowHash(captured))
                .put("poseRowsSha256",poseHash).put("triangulationRowsSha256",triangulationHash).put("pointsSha256",pointsHash)
                .put("projections",projections).put("anchorPoses",poses).put("provenance",provenance)
                .put("rows",JSONArray((0 until expectedRows).map{i->JSONArray(captured.slice(i*5 until i*5+5))}))
                .also{require(it.toString().toByteArray().size<=65536)}
        } finally {read.verify()}
    }
}
