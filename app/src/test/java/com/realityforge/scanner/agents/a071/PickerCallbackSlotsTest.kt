package com.realityforge.scanner.agents.a071

import com.realityforge.scanner.application.OperationCoordinator
import com.realityforge.scanner.domain.*
import com.realityforge.scanner.export.*
import com.realityforge.scanner.ui.navigation.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses actual001 admission/codec and actual067 durable intents; no replacement coordinator or fake store. */
class PickerCallbackSlotsTest {
    private val projectA=ProjectRevision(UUID.randomUUID().toString(),4)
    private val projectB=ProjectRevision(UUID.randomUUID().toString(),8)
    private fun ui(request:BoundExternalRequest):PendingRequest = PendingRequest(request.requestId.value,when(request.kind) {
        OperationKind.IMPORT_PHOTOS->PendingKind.IMPORT
        OperationKind.CAPTURE_PHOTO->PendingKind.CAMERA
        OperationKind.CALIBRATE_LENS->PendingKind.CALIBRATE
        OperationKind.EXPORT_CAPTURE_BUNDLE->PendingKind.CAPTURE_ARCHIVE
        OperationKind.EXPORT_ARTIFACT->PendingKind.EXPORT
        else->error("Not an external request")
    },request.project.projectId,request.project.revision,(request as? BoundExternalRequest.ArtifactExport)?.artifactKey,
        (request as? BoundExternalRequest.ArtifactExport)?.extension)
    private fun typed(request:PendingRequest)=ExportRequest(request.requestId,
        ArtifactIdentity(request.projectId,request.revision,requireNotNull(request.artifactKey),"a".repeat(64)),
        ExportOption(requireNotNull(request.extension),ExportFormat.JSON,AssetRepresentation.DIAGNOSTICS,2,contentSha256="b".repeat(64)))
    private fun artifact(coordinator:OperationCoordinator,project:ProjectRevision=projectA)=ui(requireNotNull(coordinator.bindArtifact(project,1,"asset-${project.revision}",project.revision,"json")))
    private fun temporary(block:(PendingExportStore,File)->Unit) {
        val root=Files.createTempDirectory("picker-real-store-").toFile()
        try {val file=File(root,"pending");block(PendingExportStore(file),file)} finally {root.deleteRecursively()}
    }
    @Test fun canceledImportKeepsItsLauncherOccupiedUntilItsOwnResultDrains() {
        val coordinator=OperationCoordinator()
        val a=ui(requireNotNull(coordinator.bindImport(projectA,1)))
        var slots=PickerCallbackSlots.empty().launched(a).cancel(a.requestId)
        coordinator.cancelPending(RequestId(a.requestId))
        assertFalse(slots.canLaunch(PendingKind.IMPORT))
        assertTrue(slots.awaitingCancelledResult)
        assertNull(if(slots.canLaunch(PendingKind.IMPORT)) coordinator.bindImport(projectB,2) else null)
        val (old,remaining)=slots.take(PendingKind.IMPORT);slots=remaining
        val result=PickerCallbackBinding.consume(requireNotNull(old),null,false)
        assertNull(result.accepted);assertEquals(a.requestId,result.cancelRequestId)
        assertTrue(slots.canLaunch(PendingKind.IMPORT));assertFalse(slots.awaitingCancelledResult)
        val b=ui(requireNotNull(coordinator.bindImport(projectB,2)))
        val slot=slots.launched(b).take(PendingKind.IMPORT).first!!
        val accepted=PickerCallbackBinding.consume(slot,b,false).accepted!!
        val claimed=coordinator.claimPending(RequestId(accepted.requestId),OperationKind.IMPORT_PHOTOS)!!
        assertEquals(projectB,claimed.second.source)
    }
    @Test fun lateImportACannotClearOrCancelNewCalibrationB() {
        val coordinator=OperationCoordinator()
        val a=ui(requireNotNull(coordinator.bindImport(projectA,1)))
        var slots=PickerCallbackSlots.empty().launched(a).cancel(a.requestId)
        coordinator.cancelPending(RequestId(a.requestId))
        val b=ui(requireNotNull(coordinator.bindCalibration(projectB,2)))
        slots=slots.launched(b)
        val (old,remaining)=slots.take(PendingKind.IMPORT);slots=remaining
        val result=PickerCallbackBinding.consume(old!!,b,false)
        assertNull(result.accepted);assertEquals(b,result.pendingAfter)
        coordinator.cancelPending(RequestId(result.cancelRequestId!!))
        assertEquals(b.requestId,coordinator.pendingRequests().single().requestId.value)
        assertNull(slots.take(PendingKind.IMPORT).first) // A second callback cannot drain B's different launcher.
        val accepted=PickerCallbackBinding.consume(slots.take(PendingKind.CALIBRATE).first!!,b,false).accepted!!
        assertEquals(projectB,coordinator.claimPending(RequestId(accepted.requestId),OperationKind.CALIBRATE_LENS)!!.second.source)
    }
    @Test fun lateCameraACannotConsumeNewCameraBOrChangeItsOwner() {
        val coordinator=OperationCoordinator()
        val a=ui(requireNotNull(coordinator.bindCapture(projectA,1)))
        coordinator.cancelPending(RequestId(a.requestId))
        val b=ui(requireNotNull(coordinator.bindCapture(projectB,2)))
        val oldPhoto=PickerCallbackBinding.consume(PickerCallbackSlot(a),b,false)
        assertNull(oldPhoto.accepted);assertEquals(b,oldPhoto.pendingAfter)
        coordinator.cancelPending(RequestId(oldPhoto.cancelRequestId!!))
        assertEquals(b.requestId,coordinator.pendingRequests().single().requestId.value)
        val actual=PickerCallbackBinding.consume(PickerCallbackSlot(b),b,false).accepted!!
        assertEquals(projectB,coordinator.claimPending(RequestId(actual.requestId),OperationKind.CAPTURE_PHOTO)!!.second.source)
    }
    @Test fun activityOrProcessStateRestoreKeepsOriginalChooserBinding() {
        val first=OperationCoordinator()
        val bound=requireNotNull(first.bindImport(projectA,1));val a=ui(bound)
        val savedSlots=PickerCallbackSlots.empty().launched(a).encode()
        val savedDomain=BoundExternalRequestCodec.encode(bound)
        val restoredCoordinator=OperationCoordinator().apply {restorePending(listOf(BoundExternalRequestCodec.decode(savedDomain)))}
        val restoredSlots=PickerCallbackSlots.restore(savedSlots)
        val callback=restoredSlots.take(PendingKind.IMPORT).first!!
        val accepted=PickerCallbackBinding.consume(callback,PendingRequest.restore(a.encode()),false).accepted!!
        assertEquals(a,accepted)
        val claimed=restoredCoordinator.claimPending(RequestId(accepted.requestId),OperationKind.IMPORT_PHOTOS)!!
        assertEquals(projectA,claimed.first.project);assertEquals(projectA,claimed.second.source)
    }
    @Test fun restoredCancelledTombstoneCannotBeReusedBeforeCallback() {
        val coordinator=OperationCoordinator();val a=ui(requireNotNull(coordinator.bindCalibration(projectA,1)))
        val restored=PickerCallbackSlots.restore(PickerCallbackSlots.empty().launched(a).cancel(a.requestId).encode())
        assertFalse(restored.canLaunch(PendingKind.CALIBRATE));assertTrue(restored.awaitingCancelledResult)
        val (slot,remaining)=restored.take(PendingKind.CALIBRATE)
        assertTrue(slot!!.cancelled);assertNull(PickerCallbackBinding.consume(slot,a,false).accepted)
        assertTrue(remaining.canLaunch(PendingKind.CALIBRATE))
    }
    @Test fun modelAndArchiveExportsShareOneImmutableLauncherSlot() {
        val coordinator=OperationCoordinator();val a=artifact(coordinator)
        val occupied=PickerCallbackSlots.empty().launched(a)
        assertFalse(occupied.canLaunch(PendingKind.CAPTURE_ARCHIVE))
        coordinator.cancelPending(RequestId(a.requestId))
        val (cancelled,empty)=occupied.cancel(a.requestId).take(PendingKind.CAPTURE_ARCHIVE)
        assertEquals(PendingKind.EXPORT,cancelled!!.request.kind);assertNull(PickerCallbackBinding.consume(cancelled,null,false).accepted)
        val archive=ui(requireNotNull(coordinator.bindCaptureBundle(projectB,2)))
        val slots=empty.launched(archive)
        assertFalse(slots.canLaunch(PendingKind.EXPORT))
        val returned=slots.take(PendingKind.EXPORT).first!!
        assertEquals(PendingKind.CAPTURE_ARCHIVE,returned.request.kind)
        val accepted=PickerCallbackBinding.consume(returned,archive,false).accepted!!
        assertEquals(projectB,coordinator.claimPending(RequestId(accepted.requestId),OperationKind.EXPORT_CAPTURE_BUNDLE)!!.second.source)
    }
    @Test fun exportRestoreRequiresEveryFixedBindingField() {
        val coordinator=OperationCoordinator();val a=artifact(coordinator);val fixed=typed(a)
        assertTrue(PickerCallbackBinding.matchesStoredExport(a,fixed))
        for(changed in listOf(a.copy(requestId=UUID.randomUUID().toString()),a.copy(projectId=projectB.projectId),
            a.copy(revision=a.revision+1),a.copy(artifactKey="different"),a.copy(extension="glb")))
            assertFalse(PickerCallbackBinding.matchesStoredExport(changed,fixed))
        assertFalse(PickerCallbackBinding.matchesStoredExport(a,null))
    }
    @Test fun mismatchedStoredExportCancelsOnlyActualAdmissionAndPreservesStoredEvidence() = temporary {store,file->
        val coordinator=OperationCoordinator();val a=artifact(coordinator)
        val otherCoordinator=OperationCoordinator();val b=artifact(otherCoordinator,projectB)
        store.save(typed(b));val original=file.readBytes()
        val accepted=PickerCallbackBinding.consume(PickerCallbackSlot(a),a,false).accepted!!
        assertFalse(PickerCallbackBinding.matchesStoredExport(accepted,store.restore()))
        coordinator.cancelPending(RequestId(accepted.requestId)) // The production finally cancels exactly this unclaimed ID.
        assertTrue(coordinator.pendingRequests().isEmpty())
        assertEquals(b.requestId,otherCoordinator.pendingRequests().single().requestId.value)
        assertEquals(typed(b),store.restore());assertArrayEquals(original,file.readBytes())
    }
    @Test fun startupReadCannotResurrectAConsumedExportEvenWhenCurrentPendingIsEmpty() = temporary {store,_->runBlocking {
        val coordinator=OperationCoordinator();val a=artifact(coordinator);store.save(typed(a))
        val slots=PickerCallbackSlots.empty().launched(a)
        var pending:PendingRequest?=null;val pendingEpoch=0L;var launchEpoch=0L
        val readStarted=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>()
        val startup=async {
            val snapshot=ExportRestoreSnapshot(pendingEpoch,launchEpoch,pending)
            val captured=store.restore()!!;readStarted.complete(Unit);resume.await()
            if(PickerCallbackBinding.mayRestore(snapshot,pendingEpoch,launchEpoch,pending,false))
                pending=PendingRequest(captured.requestId,PendingKind.EXPORT,captured.artifact.projectId,captured.artifact.revision,captured.artifact.artifactKey,captured.option.id)
        }
        readStarted.await()
        val callback=slots.take(PendingKind.EXPORT).first!!;launchEpoch++
        val rejected=PickerCallbackBinding.consume(callback,pending,false)
        coordinator.cancelPending(RequestId(rejected.cancelRequestId!!));store.clear(a.requestId)
        resume.complete(Unit);startup.await()
        assertNull(pending);assertNull(store.restore());assertTrue(coordinator.pendingRequests().isEmpty())
    } }
    @Test fun startupReadCannotUndoPendingCreateAndCancelMutations() {
        val snapshot=ExportRestoreSnapshot(7,3,null)
        assertFalse(PickerCallbackBinding.mayRestore(snapshot,9,3,null,false))
        assertFalse(PickerCallbackBinding.mayRestore(snapshot,7,4,null,false))
        assertFalse(PickerCallbackBinding.mayRestore(snapshot,7,3,null,true))
    }
    @Test fun startupWithSavedCancelledExportCannotRestoreItsStillValidDiskIntent() = temporary {store,_->
        val coordinator=OperationCoordinator();val a=artifact(coordinator);store.save(typed(a))
        coordinator.cancelPending(RequestId(a.requestId))
        val slots=PickerCallbackSlots.restore(PickerCallbackSlots.empty().launched(a).cancel(a.requestId).encode())
        val snapshot=ExportRestoreSnapshot(0,0,null)
        var pending:PendingRequest?=null
        if(PickerCallbackBinding.mayRestore(snapshot,0,0,pending,false) && slots.permitsRestore(a)) pending=a
        assertNull(pending);assertFalse(slots.canLaunch(PendingKind.EXPORT))
        assertEquals(typed(a),store.restore()) // A cancelled record is preserved until exact cleanup, not rebound.
        val (slot,drained)=slots.take(PendingKind.EXPORT)
        assertNull(PickerCallbackBinding.consume(slot!!,pending,false).accepted)
        store.clear(a.requestId)
        assertNull(store.restore());assertTrue(drained.canLaunch(PendingKind.EXPORT))
    }
    @Test fun recreationAfterExportCallbackCannotRestoreIntentAwaitingAsyncCleanup() = temporary {store,file->
        val coordinator=OperationCoordinator();val a=artifact(coordinator);store.save(typed(a))
        val original=file.readBytes()
        val (callback,drained)=PickerCallbackSlots.empty().launched(a).take(PendingKind.EXPORT)
        val accepted=PickerCallbackBinding.consume(callback!!,a,false).accepted!!
        coordinator.cancelPending(RequestId(accepted.requestId)) // Activity is destroyed before C1 transfer admission.
        val recreatedSlots=PickerCallbackSlots.restore(drained.encode())
        val recreatedSnapshot=ExportRestoreSnapshot(2,2,null)
        assertTrue(PickerCallbackBinding.mayRestore(recreatedSnapshot,2,2,null,false))
        assertFalse(recreatedSlots.permitsRestore(a)) // Epochs are already post-consumption; saved origin still rejects A.
        assertArrayEquals(original,file.readBytes());assertEquals(typed(a),store.restore())
        assertTrue(recreatedSlots.canLaunch(PendingKind.EXPORT)) // The actual platform result has drained.
        val import=ui(requireNotNull(coordinator.bindImport(projectB,2)))
        val afterOtherPicker=recreatedSlots.launched(import).take(PendingKind.IMPORT).second
        assertFalse(PickerCallbackSlots.restore(afterOtherPicker.encode()).permitsRestore(a))
        coordinator.cancelPending(RequestId(import.requestId))
        val archive=ui(requireNotNull(coordinator.bindCaptureBundle(projectB,2)))
        val afterArchive=afterOtherPicker.launched(archive).take(PendingKind.EXPORT).second
        assertFalse(PickerCallbackSlots.restore(afterArchive.encode()).permitsRestore(a))
        coordinator.cancelPending(RequestId(archive.requestId));store.clear(a.requestId)
        val b=artifact(coordinator,projectB)
        assertTrue(afterArchive.permitsRestore(b))
        assertFalse(afterArchive.launchFailed(b.requestId).permitsRestore(a))
    }
    @Test fun restoredTerminalExportReleasesItsActualC1AdmissionAndPreservesOtherStoredRequests() = temporary {store,file->
        for(cancelled in listOf(false,true)) {
            val original=OperationCoordinator()
            val bound=requireNotNull(original.bindArtifact(projectA,1,"asset-${projectA.revision}",projectA.revision,"json"))
            val a=ui(bound);store.save(typed(a))
            val launched=PickerCallbackSlots.empty().launched(a)
            val terminalSlots=if(cancelled) launched.cancel(a.requestId) else launched.take(PendingKind.EXPORT).second
            // Death occurs before cancellation/finally: the persisted001 codec still contains A.
            val restored=OperationCoordinator().apply {restorePending(listOf(BoundExternalRequestCodec.decode(BoundExternalRequestCodec.encode(bound))))}
            val slots=PickerCallbackSlots.restore(terminalSlots.encode())
            assertEquals(a.requestId,restored.pendingRequests().single().requestId.value)
            val disk=store.restore()!!
            val recovered=PendingRequest(disk.requestId,PendingKind.EXPORT,disk.artifact.projectId,disk.artifact.revision,disk.artifact.artifactKey,disk.option.id)
            val terminal=requireNotNull(slots.terminalExport(recovered))
            restored.cancelPending(RequestId(terminal.requestId));store.clear(terminal.requestId)
            assertNull(store.restore());assertTrue(restored.pendingRequests().isEmpty())
            val b=artifact(restored,projectB) // Admission is released without replaying the consumed result.
            store.save(typed(b));val bytes=file.readBytes()
            assertNull(slots.terminalExport(b));assertArrayEquals(bytes,file.readBytes())
            assertEquals(b.requestId,restored.pendingRequests().single().requestId.value)
            for(changed in listOf(a.copy(projectId=projectB.projectId),a.copy(revision=a.revision+1),
                a.copy(artifactKey="other"),a.copy(extension="glb"))) assertNull(slots.terminalExport(changed))
            store.clear(b.requestId)
        }
    }
    @Test fun restoreRequiresAnUncancelledMatchingSlotOrNoLaunchYet() {
        val first=OperationCoordinator();val a=artifact(first)
        val other=OperationCoordinator();val b=artifact(other,projectB)
        val slot=PickerCallbackSlots.empty().launched(a)
        assertTrue(slot.permitsRestore(a));assertFalse(slot.permitsRestore(b))
        assertTrue(PickerCallbackSlots.empty().permitsRestore(a))
        assertFalse(PickerCallbackSlots.restore("invalid").permitsRestore(a))
    }
    @Test fun startupWithAnObservedPendingRequestNeverRecreatesItAfterCompletion() {
        val coordinator=OperationCoordinator();val a=artifact(coordinator)
        val snapshot=ExportRestoreSnapshot(7,3,a)
        assertFalse(PickerCallbackBinding.mayRestore(snapshot,7,3,null,false))
        assertTrue(PickerCallbackBinding.mayRestore(ExportRestoreSnapshot(7,3,null),7,3,null,false))
    }
    @Test fun synchronousLaunchFailureReleasesOnlyThatSlot() {
        val coordinator=OperationCoordinator();val a=ui(requireNotNull(coordinator.bindImport(projectA,1)))
        coordinator.cancelPending(RequestId(a.requestId));val b=artifact(coordinator,projectB)
        val slots=PickerCallbackSlots.empty().launched(a).launched(b).cancel(b.requestId).launchFailed(a.requestId)
        assertTrue(slots.canLaunch(PendingKind.IMPORT));assertFalse(slots.canLaunch(PendingKind.CAPTURE_ARCHIVE))
        assertEquals(b,slots.take(PendingKind.EXPORT).first!!.request)
    }
    @Test fun malformedOrDuplicateSavedSlotsFailClosedWithVisibleBlockedState() {
        val coordinator=OperationCoordinator();val a=ui(requireNotNull(coordinator.bindImport(projectA,1)))
        val valid=PickerCallbackSlots.empty().launched(a).encode();val line=valid.substringAfter('\n')
        coordinator.cancelPending(RequestId(a.requestId))
        val export=artifact(coordinator)
        val consumed=PickerCallbackSlots.empty().launched(export).take(PendingKind.EXPORT).second.encode().substringAfter('\n')
        val wrongKind=line.replace("IMPORT:","CONSUMED_EXPORT:")
        for(bad in listOf("0", "2\n$line\n$line", "2\nIMPORT:2:invalid", "2\n$consumed\n$consumed", "2\n$wrongKind", "x".repeat(65537))) {
            val restored=PickerCallbackSlots.restore(bad)
            assertTrue(restored.blocked);assertTrue(restored.awaitingCancelledResult)
            assertFalse(restored.canLaunch(PendingKind.IMPORT));assertFalse(restored.canLaunch(PendingKind.EXPORT))
        }
    }
    @Test fun staleRevisionOrBusyCallbackCannotClaimANewerRequest() {
        val coordinator=OperationCoordinator();val a=ui(requireNotNull(coordinator.bindImport(projectA,1)))
        val changed=a.copy(revision=a.revision+1)
        assertEquals(changed,PickerCallbackBinding.consume(PickerCallbackSlot(a),changed,false).pendingAfter)
        assertNull(PickerCallbackBinding.consume(PickerCallbackSlot(a),a,true).accepted)
    }
}
