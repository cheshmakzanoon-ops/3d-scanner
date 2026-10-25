package com.realityforge.scanner.engine

import java.io.File
import org.json.JSONObject

/** Publication admission only: no inference about physical surface completeness. */
internal object MeshPublicationDecision {
    data class Rejection(val code: String, val message: String)

    fun rejection(result: JSONObject, glb: File, ply: File): Rejection? {
        val mesh=result.optJSONObject("mesh")
        // Keep the production predicate ordering and strict artifact-size boundaries.
        val rejection = when {
            mesh == null -> Rejection("MESH_RESULT_MISSING", "Native surface result is unavailable")
            mesh.optInt("triangleCount") < 40 -> Rejection("MESH_TRIANGLE_COUNT_BELOW_MINIMUM",
                "Surface has fewer than 40 triangles required for publication")
            mesh.optInt("vertexCount") < 30 -> Rejection("MESH_VERTEX_COUNT_BELOW_MINIMUM",
                "Surface has fewer than 30 vertices required for publication")
            !glb.isFile -> Rejection("MESH_GLB_MISSING", "Surface GLB artifact is unavailable")
            !ply.isFile -> Rejection("MESH_PLY_MISSING", "Surface PLY artifact is unavailable")
            glb.length() <= 100 -> Rejection("MESH_GLB_TOO_SMALL", "Surface GLB artifact does not exceed 100 bytes")
            ply.length() <= 100 -> Rejection("MESH_PLY_TOO_SMALL", "Surface PLY artifact does not exceed 100 bytes")
            else -> return null
        }
        // Historically native meshError took precedence only on rejected output.
        // Do not let an extraneous native error change an otherwise accepted result.
        return if (result.has("meshError") && !result.isNull("meshError"))
            Rejection("NATIVE_MESH_REJECTED", result.optString("meshError", rejection.message))
        else rejection
    }
}
