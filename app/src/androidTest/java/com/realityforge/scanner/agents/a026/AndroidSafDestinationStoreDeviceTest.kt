package com.realityforge.scanner.agents.a026

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.safexport.AndroidSafDestinationStore
import com.realityforge.scanner.data.safexport.BoundDestinationRequest
import com.realityforge.scanner.data.safexport.DestinationCapabilities
import com.realityforge.scanner.data.safexport.DestinationOwnership
import com.realityforge.scanner.data.safexport.DestinationUnavailableException
import com.realityforge.scanner.data.safexport.ExportSourceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSafDestinationStoreDeviceTest {
    @Test
    fun nonContentDestinationIsRejectedBeforeResolverAccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidSafDestinationStore(context)
        val request = BoundDestinationRequest(
            requestId = "request-device",
            sourceIdentity = ExportSourceIdentity(
                projectId = "project",
                revision = 1,
                artifactKey = "artifact",
                representation = "archive/zip",
            ),
            destinationUri = "file:///sdcard/Download/untrusted.zip",
            ownership = DestinationOwnership.NEWLY_CREATED,
            capabilities = DestinationCapabilities.CONSERVATIVE,
        )

        try {
            store.prepare(request)
            fail("Non-SAF destination should be rejected")
        } catch (_: DestinationUnavailableException) {
            // Expected: caller must use a content:// URI returned by the system picker.
        }
    }
}
