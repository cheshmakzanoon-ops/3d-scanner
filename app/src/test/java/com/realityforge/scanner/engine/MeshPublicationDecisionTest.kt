package com.realityforge.scanner.engine

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeshPublicationDecisionTest {
    private fun withFiles(block: (File,File)->Unit) {
        val dir=Files.createTempDirectory("mesh-publication-test").toFile()
        try {
            val glb=File(dir,"surface.glb").apply { writeBytes(ByteArray(101)) }
            val ply=File(dir,"surface.ply").apply { writeBytes(ByteArray(101)) }
            block(glb,ply)
        } finally { dir.deleteRecursively() }
    }
    private fun result(triangles:Int=40,vertices:Int=30)=JSONObject().put("mesh",
        JSONObject().put("triangleCount",triangles).put("vertexCount",vertices))
    private fun code(result:JSONObject,glb:File,ply:File)=
        requireNotNull(MeshPublicationDecision.rejection(result,glb,ply)).code

    @Test fun everyRejectionHasSpecificFiniteReasonInOriginalPredicateOrder() = withFiles { glb,ply ->
        assertEquals("MESH_RESULT_MISSING",code(JSONObject(),glb,ply))
        assertEquals("MESH_TRIANGLE_COUNT_BELOW_MINIMUM",code(result(39,29),glb,ply))
        assertEquals("MESH_VERTEX_COUNT_BELOW_MINIMUM",code(result(40,29),glb,ply))
        glb.delete();ply.delete()
        assertEquals("MESH_GLB_MISSING",code(result(),glb,ply))
        glb.writeBytes(ByteArray(0))
        assertEquals("MESH_PLY_MISSING",code(result(),glb,ply))
        ply.writeBytes(ByteArray(100))
        assertEquals("MESH_GLB_TOO_SMALL",code(result(),glb,ply))
        glb.writeBytes(ByteArray(101))
        assertEquals("MESH_PLY_TOO_SMALL",code(result(),glb,ply))
    }
    @Test fun exactExistingThresholdsRemainUnchanged() = withFiles { glb,ply ->
        assertNull(MeshPublicationDecision.rejection(result(),glb,ply))
        glb.writeBytes(ByteArray(100))
        assertEquals("MESH_GLB_TOO_SMALL",code(result(),glb,ply))
        glb.writeBytes(ByteArray(101));ply.writeBytes(ByteArray(100))
        assertEquals("MESH_PLY_TOO_SMALL",code(result(),glb,ply))
        ply.writeBytes(ByteArray(101))
        assertNull(MeshPublicationDecision.rejection(result(),glb,ply))
    }
    @Test fun nativeFailureMessagePrecedenceDoesNotChangeAcceptance() = withFiles { glb,ply ->
        val rejected=JSONObject().put("meshError","Native rejection detail")
        val reason=requireNotNull(MeshPublicationDecision.rejection(rejected,glb,ply))
        assertEquals("NATIVE_MESH_REJECTED",reason.code)
        assertEquals("Native rejection detail",reason.message)
        assertNull(MeshPublicationDecision.rejection(result().put("meshError","ignored on accepted output"),glb,ply))
    }
    @Test fun missingCountsAndDirectoriesRemainRejectedWithoutInventingCoverage() = withFiles { glb,ply ->
        assertEquals("MESH_TRIANGLE_COUNT_BELOW_MINIMUM",code(JSONObject().put("mesh",JSONObject()),glb,ply))
        glb.delete();glb.mkdir()
        val reason=requireNotNull(MeshPublicationDecision.rejection(result(),glb,ply))
        assertEquals("MESH_GLB_MISSING",reason.code)
        assertFalse(reason.message.contains(glb.path))
        assertFalse(reason.message.contains("coverage",ignoreCase=true))
    }
    @Test fun acceptanceMatchesOriginalGateAcrossGeometryAndArtifactMatrix() = withFiles { glb,ply ->
        for (triangles in listOf(0,39,40,41)) for (vertices in listOf(0,29,30,31)) {
            for (glbBytes in listOf(-1,0,100,101)) for (plyBytes in listOf(-1,0,100,101)) {
                if (glbBytes < 0) glb.delete() else glb.writeBytes(ByteArray(glbBytes))
                if (plyBytes < 0) ply.delete() else ply.writeBytes(ByteArray(plyBytes))
                val input=result(triangles,vertices)
                val mesh=input.optJSONObject("mesh")
                val original=mesh!=null && mesh.optInt("triangleCount")>=40 &&
                    mesh.optInt("vertexCount")>=30 && glb.isFile && ply.isFile &&
                    glb.length()>100 && ply.length()>100
                assertEquals(original,MeshPublicationDecision.rejection(input,glb,ply)==null)
            }
        }
    }
    @Test fun nullNativeErrorUsesSpecificReasonWhileEmptyNativeMessageIsPreserved() = withFiles { glb,ply ->
        assertEquals("MESH_RESULT_MISSING",code(JSONObject().put("meshError",JSONObject.NULL),glb,ply))
        val empty=requireNotNull(MeshPublicationDecision.rejection(JSONObject().put("meshError",""),glb,ply))
        assertEquals("NATIVE_MESH_REJECTED",empty.code)
        assertEquals("",empty.message)
    }

}
