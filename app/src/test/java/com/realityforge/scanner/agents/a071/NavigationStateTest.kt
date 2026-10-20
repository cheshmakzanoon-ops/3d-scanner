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
    @Test fun savedPickerNeverRedirectsAfterSelectionOrRevisionChange() {
        val request = PendingRequest.create(PendingKind.EXPORT, "project-a", 7, "asset-7", "glb")
        assertEquals(request, PendingRequest.restore(request.encode()))
        assertTrue(request.accepts("project-a", 7, "asset-7", false))
        assertFalse(request.accepts("project-b", 7, "asset-7", false))
        assertFalse(request.accepts("project-a", 8, "asset-7", false))
        assertFalse(request.accepts("project-a", 7, "asset-8", false))
        assertFalse(request.accepts("project-a", 7, "asset-7", true))
    }
    @Test fun malformedRestorationFailsClosedAndRequestHasUniqueIdentity() {
        assertNull(PendingRequest.restore("EXPORT\np\n7"))
        val first = PendingRequest.create(PendingKind.IMPORT, "p", 0)
        assertNotEquals(first.requestId, PendingRequest.create(PendingKind.IMPORT, "p", 0).requestId)
    }
}
