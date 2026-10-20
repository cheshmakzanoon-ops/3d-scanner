package com.realityforge.scanner.agents.a071
import com.realityforge.scanner.ui.navigation.*
import org.junit.Assert.*
import org.junit.Test
class NavigationStateTest {
    @Test fun routeRoundTripAndBackAreProjectBound() {
        for (destination in listOf(Destination.REVIEW, Destination.CAPTURE, Destination.PROCESSING, Destination.STUDIO, Destination.EXPORT)) {
            val route = AppRoute(destination, "project-a")
            assertEquals(route, AppRoute.restore(route.encode()))
            assertEquals(if (destination == Destination.REVIEW) null else "project-a", route.back().projectId)
        }
        assertEquals(Destination.LIBRARY, AppRoute.restore("STUDIO:").destination)
    }
    @Test fun savedPickerRetainsInitiatingProjectAndArtifact() {
        val request=PendingRequest.create(PendingKind.EXPORT,"project-a",7,"asset-7","glb")
        val restored=requireNotNull(PendingRequest.restore(request.encode()))
        assertEquals(request,restored)
        assertEquals("project-a",restored.projectId)
        assertEquals(7L,restored.revision)
        assertNull(PendingRequest.restore(request.encode().replaceFirst("1\n","2\n")))
        assertNull(PendingRequest.restore(request.encode().replace("\nglb","\nexecutable")))
        assertNull(PendingRequest.restore("x".repeat(8193)))
    }
    @Test fun malformedRestorationFailsClosedAndRequestHasUniqueIdentity() {
        assertNull(PendingRequest.restore("EXPORT\np\n7"))
        val first = PendingRequest.create(PendingKind.IMPORT, "p", 0)
        assertNotEquals(first.requestId, PendingRequest.create(PendingKind.IMPORT, "p", 0).requestId)
    }
}
