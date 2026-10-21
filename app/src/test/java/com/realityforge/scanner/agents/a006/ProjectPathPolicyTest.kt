package com.realityforge.scanner.agents.a006

import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ProjectPathPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun validProjectArtifactAndOriginalStayUnderCanonicalRoot() {
        val root = temporary.newFolder("projects")
        val paths = ProjectPathPolicy(root)
        val project = UUID.randomUUID().toString()
        val photoId = UUID.randomUUID().toString()
        val key = "sparse-m19-r12-0123456789abcdef"
        val directory = paths.projectDirectory(project)
        val artifact = paths.artifact(project, key, "textured.glb")
        val photo = paths.photoFile(photo(project, photoId))
        assertTrue(directory.canonicalPath.startsWith(root.canonicalPath))
        assertEquals("$key.textured.glb", artifact.name)
        assertEquals("$photoId.image", photo.name)
        assertEquals(project, paths.requireOwnedFile(artifact).projectId)
        assertEquals(12L, paths.artifactRevision(key))
    }

    @Test fun traversalInvalidIdentifiersAndUnknownArtifactKindsAreRejected() {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        val project = UUID.randomUUID().toString()
        expectIllegal { paths.projectDirectory("../escape") }
        expectIllegal { paths.projectDirectory(project.uppercase()) }
        expectIllegal { paths.artifact(project, "../../scan", "json") }
        expectIllegal { paths.artifact(project, "sparse-m19-r0-0123456789abcdef", "apk") }
        expectIllegal { paths.projectFile(project, "../camera.json") }
        expectIllegal { paths.requireOwnedFile(File(temporary.root, "outside.txt")) }
        val badPhoto = photo(project, UUID.randomUUID().toString()).copy(filename = "../../outside.image")
        expectIllegal { paths.photoFile(badPhoto) }
    }

    @Test fun projectDirectorySymlinkCannotEscapePrivateStorage() {
        val root = temporary.newFolder("projects")
        val outside = temporary.newFolder("outside")
        val paths = ProjectPathPolicy(root)
        val project = UUID.randomUUID().toString()
        Files.createSymbolicLink(File(root, project).toPath(), outside.toPath())
        expectIllegal { paths.projectDirectory(project) }
    }

    private fun photo(project: String, id: String) = ScanPhoto(
        id = id,
        projectId = project,
        filename = "$id.image",
        sha256 = "0".repeat(64),
        bytes = 1,
        width = 1,
        height = 1,
        focal35mm = null,
        sharpness = 0.0,
        darkFraction = 0.0,
        brightFraction = 0.0,
        addedAt = 0,
    )

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("Unsafe path was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
