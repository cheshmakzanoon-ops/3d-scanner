package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.NativeTrackBatch
import com.realityforge.scanner.engine.tracks.SourceObservation
import com.realityforge.scanner.engine.tracks.digest
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Fixed-intrinsic, <=12-camera production adapter. Pixel rows retain exact observed
 * feature coordinates; stable identities retain the admitted graph's local ordering.
 * No lens estimation or fabricated loop evidence is performed in Kotlin. */
internal object TypedBundleAdapter {
    data class Input(
        val cameraRows: DoubleArray, val seedRows: DoubleArray, val pixelRows: DoubleArray,
        val intrinsicRows: DoubleArray, val groupIds: Array<String>, val photoIds: Array<String>,
        val sourceTrackIds: Array<String>, val measured: Boolean,
        val sourceCameras: List<Set<Int>>,
    )

    fun prepare(views: List<SparseView>, batch: NativeTrackBatch, groups: List<Map<Int,Int>>,
        cameraRows: DoubleArray, seedRows: DoubleArray, measuredCalibrationSha256: String?): Input {
        require(views.size in 3..12 && batch.photoIds.size==views.size)
        require(cameraRows.size==views.size*12 && cameraRows.all(Double::isFinite))
        require(seedRows.size%7==0 && seedRows.size/7 in 24..15000 && seedRows.all(Double::isFinite))
        require(groups.size==batch.trackIds.size)
        require(measuredCalibrationSha256==null || measuredCalibrationSha256.matches(Regex("[0-9a-f]{64}")))
        val rows=batch.rows
        require(rows.size/7==groups.sumOf { it.size }) { "Observation snapshot is incomplete" }
        val seeded=HashSet<Int>()
        for(base in seedRows.indices step 7) {
            require(seeded.add(index(seedRows[base],groups.size))) { "Duplicate bundle seed" }
            require((4..6).all { seedRows[base+it] in 0.0..255.0 })
        }
        for((track,group) in groups.withIndex()) {
            require(group.size>=2 && group.keys.all { it in views.indices })
            require(group.all { (camera,key) -> key in views[camera].colors.indices })
            val stable=group.entries.minOf { SourceObservation(batch.photoIds[it.key],it.value).identity() }
            require(stable==batch.trackIds[track]) { "Graph snapshot and stable track mapping disagree" }
        }
        val pixels=ArrayList<Double>()
        for(base in rows.indices step 7) {
            val track=index(rows[base],groups.size);val camera=index(rows[base+1],views.size)
            val key=requireNotNull(groups[track][camera]);val view=views[camera]
            val normalized=view.normalized(key)
            require(rows[base+2]==normalized.first && rows[base+3]==normalized.second) { "Native row refers to a different source feature" }
            val color=view.colors[key]
            require(rows[base+4]==((color shr 16) and 255).toDouble() && rows[base+5]==((color shr 8) and 255).toDouble() && rows[base+6]==(color and 255).toDouble())
            if(track !in seeded)continue
            require(view.xy[2*key].toDouble() in -.5..(view.width-.5) && view.xy[2*key+1].toDouble() in -.5..(view.height-.5))
            pixels.add(track.toDouble());pixels.add(camera.toDouble())
            pixels.add(view.xy[2*key].toDouble());pixels.add(view.xy[2*key+1].toDouble())
            for(k in 4..6)pixels.add(rows[base+k])
        }
        require(pixels.size/7 in 32..100000)
        val intrinsics=DoubleArray(views.size*11)
        val identities=Array(views.size) { camera ->
            val view=views[camera];val p=requireNotNull(view.projection)
            val row=doubleArrayOf(view.width.toDouble(),view.height.toDouble(),p.fx,p.fy,p.cx,p.cy,p.k1,p.k2,p.p1,p.p2,p.k3)
            row.copyInto(intrinsics,camera*11)
            // Unknown estimated lenses never manufacture shared calibration evidence.
            val lens=measuredCalibrationSha256?.let { "measured-project:$it" } ?: "unknown-estimated:${batch.photoIds[camera]}"
            digest(("typed-bundle-fixed-v1|$lens|"+row.joinToString(":") { java.lang.Double.toHexString(it) }).toByteArray(Charsets.UTF_8))
        }
        return Input(cameraRows.copyOf(),seedRows.copyOf(),pixels.toDoubleArray(),intrinsics,identities,
            batch.photoIds.toTypedArray(),batch.trackIds.toTypedArray(),measuredCalibrationSha256!=null,
            groups.map { it.keys.toSet() })
    }

    /** Validate actual native receipts before accepting any pose or provenance. */
    fun validateResult(input: Input, result: JSONObject) {
        require(result.getInt("schema")==6 && result.getInt("bundleProblemSchema")==1)
        require(result.getString("representation")=="sparse_points" && result.getString("scale")=="arbitrary")
        require(result.getString("gaugeMode")=="pose_and_baseline" && result.getInt("anchorCamerasFixed")==1)
        require(result.getString("intrinsicPolicy").isNotBlank() && result.getInt("acceptedIntrinsicGroups")==0)
        val count=input.photoIds.size
        val cameras=result.getJSONArray("cameras");val models=result.getJSONArray("cameraIntrinsics")
        require(cameras.length()==count && models.length()==count && result.getInt("registeredCameras")==count)
        val declaredGroups=result.getJSONArray("intrinsicGroups")
        val cameraGroups=result.getJSONArray("cameraIntrinsicGroups")
        require(cameraGroups.length()==count && declaredGroups.length() in 1..count)
        val corrected=DoubleArray(count*12)
        for(camera in 0 until count) {
            val group=declaredGroups.getJSONObject(index(number(cameraGroups.get(camera)),declaredGroups.length()))
            require(group.getString("id")==input.groupIds[camera] && group.get("measured")==input.measured &&
                group.get("refinableFocal")==false) { "Native intrinsic provenance changed" }
            val model=models.getJSONArray(camera);require(model.length()==11)
            for(k in 0..10)require(number(model.get(k))==input.intrinsicRows[camera*11+k]) { "Fixed intrinsic changed" }
            val pose=cameras.getJSONObject(camera);require(pose.get("fixed")== (camera==0))
            val rotation=pose.getJSONArray("rotation");val translation=pose.getJSONArray("translation")
            require(rotation.length()==9 && translation.length()==3)
            for(k in 0..11)corrected[camera*12+k]=number(if(k<9)rotation.get(k) else translation.get(k-9))
            val offset=camera*12
            for(a in 0..2)for(b in 0..2)require(abs((0..2).sumOf { corrected[offset+a*3+it]*corrected[offset+b*3+it] }-
                if(a==b)1.0 else 0.0)<.01) { "Returned pose is not orthonormal" }
            val r=DoubleArray(9) { corrected[offset+it] }
            val det=r[0]*(r[4]*r[8]-r[5]*r[7])-r[1]*(r[3]*r[8]-r[5]*r[6])+r[2]*(r[3]*r[7]-r[4]*r[6])
            require(abs(det-1.0)<.01 && (9..11).all { abs(corrected[offset+it])<1e9 })
        }
        for(k in 0..11)require(abs(corrected[k]-input.cameraRows[k])<=1e-10*maxOf(1.0,abs(input.cameraRows[k]))) { "Fixed first pose changed" }
        val before=baseline(input.cameraRows);val after=baseline(corrected)
        require(before>1e-8 && abs(after-before)<=1e-8*maxOf(1.0,before)) { "Pose-and-baseline scale changed" }
        val seeded=(input.seedRows.indices step 7).map { index(input.seedRows[it],input.sourceTrackIds.size) }.toSet()
        val mapping=result.getJSONArray("stableTrackMapping");val mapped=HashSet<Int>()
        for(i in 0 until mapping.length()) {
            val row=mapping.getJSONArray(i);require(row.length()==2)
            val track=index(number(row.get(0)),input.sourceTrackIds.size)
            require(track in seeded && mapped.add(track) && row.getString(1)==input.sourceTrackIds[track]) { "Native source track mapping changed" }
        }
        require(mapped==seeded) // Native receipt includes input seeds, including subsequently rejected ones.
        require(result.getInt("inputPoints")==seeded.size)
        val observationCount=input.pixelRows.size/7
        require(result.getInt("inputObservations")==observationCount)
        val usable=result.getInt("usableObservations");val rejected=result.getInt("rejectedObservations")
        require(usable in 0..observationCount && rejected in 0..observationCount && usable+rejected==observationCount)
        for(field in listOf("robustCostBefore","robustCostAfter","medianErrorBeforeNormalized","medianErrorAfterNormalized"))
            require(number(result.get(field))>=0.0)
        val points=result.getJSONArray("points");val accepted=HashSet<Int>()
        require(points.length()==result.getInt("pointCount"))
        for(i in 0 until points.length()) {
            val row=points.getJSONArray(i);require(row.length()==9)
            for(k in 0..2)require(abs(number(row.get(k)))<1e9)
            for(k in 3..5) { val color=number(row.get(k));require(color in 0.0..255.0 && color%1.0==0.0) }
            require(number(row.get(6))>=0.0 && number(row.get(7)) in 0.0..180.0)
            val track=index(number(row.get(8)),input.sourceTrackIds.size)
            require(track in seeded && accepted.add(track))
        }
        val evidence=result.getJSONArray("trackEvidence");val supported=HashSet<Int>()
        require(evidence.length()==accepted.size)
        for(i in 0 until evidence.length()) {
            val item=evidence.getJSONObject(i);val track=index(number(item.get("track")),input.sourceTrackIds.size)
            require(track in accepted && supported.add(track))
            val rows=item.getJSONArray("supportingCameras");val seen=HashSet<Int>()
            require(rows.length()>=2)
            for(k in 0 until rows.length()) {
                val camera=index(number(rows.get(k)),count)
                require(camera in input.sourceCameras[track] && seen.add(camera))
            }
        }
        val proposals=result.getInt("loopProposalCount");val verified=result.getInt("verifiedLoopCount")
        require(proposals in 0..64 && verified in 0..proposals && result.getInt("acceptedLoopCorrections") in 0..verified)
    }

    private fun index(value: Double, count: Int): Int {
        require(value.isFinite() && value>=0 && value<count && value%1.0==0.0)
        return value.toInt()
    }
    private fun number(value: Any): Double = (value as? Number)?.toDouble()?.also { require(it.isFinite()) }
        ?: throw IllegalArgumentException("Expected finite native number")
    private fun baseline(rows: DoubleArray): Double {
        fun centre(camera: Int): DoubleArray {
            val offset=camera*12
            return DoubleArray(3) { axis -> -(0..2).sumOf { row -> rows[offset+row*3+axis]*rows[offset+9+row] } }
        }
        val a=centre(0);val b=centre(1)
        return sqrt((0..2).sumOf { (a[it]-b[it])*(a[it]-b[it]) })
    }
}
