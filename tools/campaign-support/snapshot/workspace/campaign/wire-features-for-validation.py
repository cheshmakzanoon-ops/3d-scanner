#!/usr/bin/env python3
"""Concrete Agent 002 adapter, exercised only in the local integration checkout."""
from pathlib import Path
import sys
root=Path(sys.argv[1]) if len(sys.argv)==2 else Path('/workspace/campaign/integration')
p=root/'app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt'
s=p.read_text()
imports='''import com.realityforge.scanner.engine.features.CachedFeatures
import com.realityforge.scanner.engine.features.FeatureCache
import com.realityforge.scanner.engine.features.FeatureCacheIdentity
import com.realityforge.scanner.engine.features.FeatureCacheRead
import com.realityforge.scanner.engine.features.FeatureExtractor
import com.realityforge.scanner.engine.features.FeatureFrame
import com.realityforge.scanner.engine.matching.FeatureMatcher
import com.realityforge.scanner.engine.matching.OverlapGraph
import com.realityforge.scanner.engine.matching.PairCandidate
import com.realityforge.scanner.engine.matching.PhotoFeatureSummary
'''
s=s.replace('import android.graphics.Color\n',imports)
for line in ['import org.opencv.android.Utils\n','import org.opencv.core.Core\n','import org.opencv.core.MatOfDMatch\n','import org.opencv.core.MatOfKeyPoint\n','import org.opencv.features2d.BFMatcher\n','import org.opencv.features2d.SIFT\n','import org.opencv.imgproc.Imgproc\n','import java.io.DataInputStream\n','import java.io.DataOutputStream\n','import java.io.FileInputStream\n']:
    s=s.replace(line,'')
a=s.index('    private data class Frame(')
b=s.index('    private var activeProfile:',a)
s=s[:a]+'''    private class Frame(val features: FeatureFrame) : Closeable {
        val width = features.width
        val height = features.height
        val xy = features.coordinates()
        val colors = features.colors()
        val projection get() = features.projection
        override fun close() = features.close()
    }
    private val extractor = FeatureExtractor()
    private val matcher = FeatureMatcher()
    private val decoderIdentity = "rf-upright-1600-pixel-centres-v2"
'''+s[b:]
s=s.replace('engineVersion = "m19-c2-adapter-1|m16-registration-frontier-1|m18-depth-checkpoint-1"',
            'engineVersion = "m19-c2-feature-services-2|m16-registration-frontier-1|m18-depth-checkpoint-1|${LensProjection.COORDINATE_CONVENTION}"')
s=s.replace('features = "opencv-4.12.0|sift-3500|edge-1600|cache-v1"',
            'features = "${extractor.settings.identity(decoderIdentity)}|cache-v2"')
s=s.replace('matching = "bf-l2|reciprocal-ratio-.67|gap-3|candidate-cap-24"',
            'matching = "${matcher.settingsIdentity}|summary-graph-small-exhaustive-v1"')
s=s.replace('.put("engineVersion", "m16-registration-frontier-1")',
            '.put("engineVersion", "m19-c2-feature-services-2")')
s=s.replace('        for ((index, photo) in photos.withIndex()) {\n',
            '        val summaries = ArrayList<PhotoFeatureSummary>(photos.size)\n        for ((index, photo) in photos.withIndex()) {\n',1)
s=s.replace('            loadFrame(photo, cache).close()\n', '''            loadFrame(photo, cache).use { frame ->
                val values = FloatArray(frame.features.count * 128)
                frame.features.withDescriptors { if (values.isNotEmpty()) it.get(0, 0, values) }
                summaries += PhotoFeatureSummary.siftMean(photo.id, values)
            }
''',1)
a=s.index('        // Compare local neighbors for the initial pair;')
b=s.index('        var best:',a)
s=s[:a]+'''        // The admitted <=12-photo compatibility profile uses exhaustive bounded
        // summary retrieval. Candidate evidence is never registered coverage.
        val candidateGraph = OverlapGraph(maxNeighbors = 12).build(summaries)
        val photoIndex = photos.withIndex().associate { it.value.id to it.index }
        val candidates = candidateGraph.pairs.map { pair ->
            minOf(photoIndex.getValue(pair.firstPhotoId), photoIndex.getValue(pair.secondPhotoId)) to
                maxOf(photoIndex.getValue(pair.firstPhotoId), photoIndex.getValue(pair.secondPhotoId))
        }
'''+s[b:]
a=s.index('    private fun loadFrame(')
s=s[:a]+'''    private suspend fun loadFrame(photo: ScanPhoto, cache: File): Frame {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancelled = { job?.isActive == false }
        coroutineContext.ensureActive()
        val settings = extractor.settings.identity(decoderIdentity)
        val identity = FeatureCacheIdentity(photo.sha256, decoderIdentity, settings, org.opencv.core.Core.VERSION)
        val store = FeatureCache(cache, 64L * 1024 * 1024)
        val cached = store.load(identity)
        if (cached is FeatureCacheRead.Hit) {
            val record = cached.record
            var descriptors: Mat? = null
            try {
                val owned = if (record.count == 0) Mat() else Mat(record.count, 128, CvType.CV_32F)
                descriptors = owned
                if (record.count != 0) owned.put(0, 0, record.descriptors())
                coroutineContext.ensureActive()
                val features = FeatureFrame(photo.id, photo.sha256, decoderIdentity, settings,
                    record.width, record.height, record.coordinates(), record.colors(), owned,
                    lens(photo, record.width, record.height))
                val frame = Frame(features)
                descriptors = null
                return frame
            } finally { descriptors?.release() }
        }
        val features = extractor.extract(photo.id, photo.sha256, repository.photoFile(photo),
            decoderIdentity, { width, height -> lens(photo, width, height) }, cancelled)
        try {
            val values = FloatArray(features.count * 128)
            features.withDescriptors { if (values.isNotEmpty()) it.get(0, 0, values) }
            // Cache failure is a miss/recompute decision, not a reconstruction failure.
            store.store(CachedFeatures(identity, features.width, features.height,
                features.coordinates(), features.colors(), values))
            coroutineContext.ensureActive()
            return Frame(features)
        } catch (failure: Throwable) { features.close(); throw failure }
    }

    private suspend fun matches(a: Frame, b: Frame): PairMatches {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val canonical = a.features.photoId < b.features.photoId
        val first = if (canonical) a.features else b.features
        val second = if (canonical) b.features else a.features
        val tentative = matcher.match(first, second,
            PairCandidate(first.photoId, second.photoId, 0.0, "coordinator-request-not-verified-overlap")) {
                job?.isActive == false
            }
        val rows = DoubleArray(tentative.count * 7)
        val firstKeys = IntArray(tentative.count)
        val secondKeys = IntArray(tentative.count)
        for ((index, m) in tentative.matches.withIndex()) {
            val firstKey = if (canonical) m.firstKey else m.secondKey
            val secondKey = if (canonical) m.secondKey else m.firstKey
            firstKeys[index] = firstKey
            secondKeys[index] = secondKey
            val base = index * 7
            rows[base] = if (canonical) m.ax else m.bx
            rows[base + 1] = if (canonical) m.ay else m.by
            rows[base + 2] = if (canonical) m.bx else m.ax
            rows[base + 3] = if (canonical) m.by else m.ay
            val color = a.colors[firstKey]
            rows[base + 4] = (color shr 16 and 255).toDouble()
            rows[base + 5] = (color shr 8 and 255).toDouble()
            rows[base + 6] = (color and 255).toDouble()
        }
        return PairMatches(rows, firstKeys, secondKeys)
    }
}
'''
p.write_text(s)
