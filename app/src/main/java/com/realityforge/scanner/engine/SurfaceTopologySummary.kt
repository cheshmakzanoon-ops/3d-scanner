package com.realityforge.scanner.engine

import org.json.JSONObject

/** Scalar observations only. Absence is unknown, never a zero or a manifoldness certificate. */
data class SurfaceTopologySummary(
    val nonManifoldEdges: Int? = null,
    val intersectingFacePairs: Int? = null,
    val checksIncomplete: Boolean? = null,
) {
    init { listOfNotNull(nonManifoldEdges, intersectingFacePairs).forEach { require(it >= 0) } }
    val hasMeasuredDefects: Boolean get() = nonManifoldEdges?.let { it > 0 } == true ||
        intersectingFacePairs?.let { it > 0 } == true

    companion object {
        /** Only the canonical result of ScanQualityReport.validateAndRedact reaches this adapter. */
        internal fun fromValidatedReport(report: JSONObject?): SurfaceTopologySummary {
            val topology = report?.getJSONObject("evidence")?.getJSONObject("topology") ?: return SurfaceTopologySummary()
            fun count(key: String): Int? = if (topology.isNull(key)) null else topology.getInt(key)
            return SurfaceTopologySummary(count("nonManifoldEdges"), count("selfIntersections"),
                if (topology.isNull("topologyIncomplete")) null else topology.getBoolean("topologyIncomplete"))
        }
    }
}

/** Pure display copy, shared by workspace, reopened studio and export; not an export gate. */
internal fun surfaceTopologyQualification(summary: SurfaceTopologySummary): List<String> = buildList {
    val edges = summary.nonManifoldEdges?.toString() ?: "unknown"
    val pairs = summary.intersectingFacePairs?.let {
        if (summary.checksIncomplete == true) "at least $it" else it.toString()
    } ?: "unknown"
    add("Source observed mesh: non-manifold edges: $edges; intersecting face pairs: $pairs.")
    if (summary.hasMeasuredDefects)
        add("Measured topology issues were detected. Inspect the mesh before downstream use; exporting does not repair them.")
    when (summary.checksIncomplete) {
        true -> add("Intersection checks were incomplete; additional intersections may exist.")
        null -> add("Topology audit completeness is unknown.")
        false -> if (!summary.hasMeasuredDefects && summary.nonManifoldEdges != null && summary.intersectingFacePairs != null)
            add("No non-manifold edges or intersecting face pairs were detected by these checks; this is not a manifoldness or watertightness certificate.")
    }
    if (summary.nonManifoldEdges == null || summary.intersectingFacePairs == null)
        add("Some topology measurements are unavailable; unknown does not mean zero.")
    add("Open surfaces and arbitrary scale remain. These checks do not establish printability, full-object completeness or physical accuracy.")
}
