package com.realityforge.scanner.engine.matching

/** Exhaustive verification schedule for the currently admitted small-scene profile only.
 * Summary retrieval determines priority, never eligibility or geometric connectivity.
 */
internal class AdmittedPairPlan private constructor(
    val photoIds: List<String>,
    val retrieval: CandidateGraph,
    val pairs: List<PairCandidate>,
) {
    /** Connected components of accepted native pair results, not summary similarity. */
    fun verifiedComponents(verified: Set<Pair<String, String>>): List<List<String>> {
        val scheduled = pairs.map { it.firstPhotoId to it.secondPhotoId }.toSet()
        require(verified.all { it in scheduled }) { "Verified pair was not scheduled" }
        val neighbors = photoIds.associateWith { mutableListOf<String>() }
        verified.forEach { (a, b) -> neighbors.getValue(a).add(b); neighbors.getValue(b).add(a) }
        val remaining = photoIds.toMutableSet()
        val components = mutableListOf<List<String>>()
        for (start in photoIds) if (remaining.remove(start)) {
            val queue = java.util.ArrayDeque<String>().apply { add(start) }
            val component = mutableListOf<String>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component.add(current)
                neighbors.getValue(current).sorted().forEach { if (remaining.remove(it)) queue.add(it) }
            }
            components.add(component.sorted())
        }
        return components
    }

    companion object {
        const val MAX_PHOTOS = 12
        const val MAX_PAIRS = MAX_PHOTOS * (MAX_PHOTOS - 1) / 2

        fun build(summaries: List<PhotoFeatureSummary>, checkActive: () -> Unit = {}): AdmittedPairPlan {
            require(summaries.size in 2..MAX_PHOTOS) { "Pair planning supports only 2–12 photographs" }
            checkActive()
            val retrieval = OverlapGraph(maxNeighbors = MAX_PHOTOS - 1).build(summaries)
            val ids = summaries.map { it.photoId }.sorted()
            val pairs = retrieval.pairs.toMutableList()
            val retrieved = pairs.map { it.firstPhotoId to it.secondPhotoId }.toSet()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                checkActive()
                if (ids[i] to ids[j] !in retrieved) {
                    pairs.add(PairCandidate(ids[i], ids[j], 0.0, "admitted-small-scene-exhaustive-fallback-v1"))
                }
            }
            require(pairs.size == ids.size * (ids.size - 1) / 2 && pairs.size <= MAX_PAIRS)
            checkActive()
            return AdmittedPairPlan(ids, retrieval, pairs.toList())
        }
    }
}
