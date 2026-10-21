package com.realityforge.scanner.work.execution

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.data.operations.*
import com.realityforge.scanner.data.publication.*
import com.realityforge.scanner.data.storagebudget.*
import com.realityforge.scanner.processing.resources.*
import com.realityforge.scanner.engine.DenseWorkload
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.SparsePipeline
import com.realityforge.scanner.engine.pipeline.ProductionPipelineSnapshot
import com.realityforge.scanner.engine.pipeline.sha256Utf8
import com.realityforge.scanner.engine.nativebridge.NativeBoundaryFailure
import com.realityforge.scanner.engine.diagnostics.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Process composition owns this controller. Activities only submit and observe durable work. */
class ReconstructionExecutionController(
    private val context: Context,
    private val database: ScanDatabase,
    private val repository: ProjectRepository,
) {
    val jobs: RoomJobStore = RoomJobStore(database, database.jobDao())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val leaseMutex = Mutex()
    private var worker: Job? = null
    private val nativeToken = AtomicLong(0)
    private val runningAttempt = AtomicLong(-1)
    private val runningJobId = java.util.concurrent.atomic.AtomicReference<JobId?>(null)
    private val mutableCurrent = MutableStateFlow<JobSnapshot?>(null)
    val current: StateFlow<JobSnapshot?> = mutableCurrent
    private val mutableProgress = MutableStateFlow<ExecutionProgress?>(null)
    val progress: StateFlow<ExecutionProgress?> = mutableProgress
    private val mutableDiagnosticProgress = MutableStateFlow<StageProgress?>(null)
    internal val diagnosticProgress: StateFlow<StageProgress?> = mutableDiagnosticProgress
    private val workerId = UUID.randomUUID().toString()

    private val initialization = scope.async {
        repository.recoverStorage()
        repository.dao.recoverInterrupted()
        recoverExpiredJobs()
        val queue = database.jobDao().observeQueue(1000).first()
        repository.storageBudget?.recover(queue.map { it.requestId }.toSet())
        // Opening the application never resumes expensive work after force-stop/process death.
        // Queued work remains a durable user-resumable command until a fresh visible action.
        for (row in queue) if (row.state == "QUEUED") jobs.pause(JobId(row.jobId), System.currentTimeMillis(), "REOPEN_REQUIRES_RESUME")
        mutableCurrent.value = database.jobDao().observeQueue(1000).first().firstOrNull()?.toSnapshot()
        restoreNotifications()
        if (queue.any { it.state == "RUNNING" }) scope.launch {
            delay(LEASE_MILLIS + 1000)
            recoverExpiredJobs()
            if (runningJobId.get() == null)
                mutableCurrent.value = database.jobDao().observeQueue(1000).first().firstOrNull()?.toSnapshot()
            restoreNotifications()
        }
    }

    suspend fun awaitRecovery() { initialization.await() }
    suspend fun awaitWorkerStopped() { worker?.join() }

    private suspend fun restoreNotifications() {
        com.realityforge.scanner.processing.notifications.restoreProcessingNotifications(context,
            database.jobDao().recentNotificationJobs(1000).map { it.toSnapshot() })
    }

    private suspend fun recoverExpiredJobs() = jobs.recoverExpired(System.currentTimeMillis(),
        PipelineJobCheckpointValidator(repository))

    suspend fun enqueue(projectId: String, revision: Long, operationId: String, focal35: Double): JobSnapshot = withContext(Dispatchers.IO) {
        awaitRecovery()
        require(focal35.isFinite() && focal35 in 10.0..200.0)
        val input = repository.inputSnapshot(projectId)
        check(input.revision == revision) { "Project changed before processing was admitted." }
        val selection = ResourceWorkloadScheduler.select(AndroidResourceObserver.sample(context))
        val settings = JSONObject().put("schema", 2).put("focal35", focal35)
            .put("resourceSelection", selection.encode()).toString()
        val inputHash = com.realityforge.scanner.data.InputPhotoIdentity.snapshotSha256(input)
        val calibration = LensCalibration.file(repository, projectId)
        val calibrationHash = ProductionPipelineSnapshot.calibrationSha256(calibration)
        val snapshot = jobs.enqueue(JobInputSnapshot(projectId, revision, operationId, inputHash,
            calibrationHash, sha256Utf8(settings), sha256Utf8(ENGINE_VERSION), ENGINE_VERSION,
            settingsJson = settings), System.currentTimeMillis())
        mutableCurrent.value = snapshot
        try {
            ContextCompat.startForegroundService(context, Intent(context, ReconstructionService::class.java)
                .setAction(ReconstructionService.ACTION_START))
        } catch (failure: RuntimeException) {
            mutableCurrent.value = jobs.pause(snapshot.jobId, System.currentTimeMillis(), "FOREGROUND_START_BLOCKED").snapshot
            throw IllegalStateException("Android could not start processing. Open the app and resume the saved job.", failure)
        }
        snapshot
    }

    /** START_NOT_STICKY service invokes this only after foreground promotion. */
    fun start(onUpdate: suspend (JobSnapshot) -> Unit, onIdle: () -> Unit) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            runMutex.lock()
            try {
                awaitRecovery()
                recoverExpiredJobs()
                while (isActive) {
                    val acquired = jobs.acquireNext(workerId, System.currentTimeMillis(), LEASE_MILLIS) ?: break
                    runOne(acquired, onUpdate)
                }
            } finally {
                runMutex.unlock()
                mutableProgress.value = null
                onIdle()
            }
        }
    }

    private suspend fun runOne(acquired: LeasedJob, onUpdate: suspend (JobSnapshot) -> Unit) = coroutineScope {
        runningAttempt.set(acquired.snapshot.attempt)
        runningJobId.set(acquired.snapshot.jobId)
        var completed: JobSnapshot? = null
        var lease = acquired.lease
        val input = acquired.snapshot.input
        val access = ProjectOperationRequest(input.projectId, input.requestId, ProjectOperationKind.RECONSTRUCT,
            input.projectRevision, ProjectJobIdentity(acquired.snapshot.jobId.value, lease.token))
        suspend fun emit(snapshot: JobSnapshot) { mutableCurrent.value = snapshot; onUpdate(snapshot) }
        val reservation = ReservationToken(input.requestId, input.projectId, input.projectRevision)
        val resources = ResourceCheckpointGate(context, repository.storageBudget, reservation)
        val resourceMutex = Mutex()
        val resourceFailure = java.util.concurrent.atomic.AtomicReference<ResourceCheckpointPause?>(null)
        var resourcePlan: WorkloadPlan? = null
        suspend fun checkResources() = resourceMutex.withLock { resources.check(requireNotNull(resourcePlan)) }
        var heartbeat: Job? = null
        var token = 0L
        var diagnostics: RuntimeDiagnosticRecorder? = null
        var executingPipeline: SparsePipeline? = null
        fun recordFailure(failure: Throwable): ScanFailure? = diagnostics?.recordFailure(
            executingPipeline?.activeDiagnosticStage ?: ScanStage.STORAGE, failure)
        try {
            emit(acquired.snapshot)
            diagnostics = RuntimeDiagnosticRecorder(DiagnosticScope(acquired.snapshot.jobId.value,
                Math.toIntExact(acquired.snapshot.attempt), input.projectRevision))
            if (input.engineVersion != ENGINE_VERSION || input.engineSha256 != sha256Utf8(ENGINE_VERSION))
                throw DiagnosticOperationException(ScanFailure(ScanFailureCode.INPUT_CHANGED, ScanStage.IMPORT,
                    StageOutcome.FAILED, RecoveryAction.RETRY_JOB, "scan.failure.engine_changed"))
            val settings = JSONObject(input.settingsJson)
            val selection = when (settings.getInt("schema")) {
                1 -> ResourceWorkloadSelection.COMPACT // Explicit conservative migration of old queued settings.
                2 -> ResourceWorkloadSelection.decode(settings.getJSONObject("resourceSelection"))
                else -> error("UNSUPPORTED_SETTINGS")
            }
            val inputSize = repository.inputSnapshot(input.projectId).photos.size
            resourcePlan = selection.resourcePlan(inputSize)
            heartbeat = launch {
                while (isActive) {
                    delay(10_000)
                    try { checkResources() } catch (paused: ResourceCheckpointPause) {
                        resourceFailure.set(paused)
                        nativeToken.get().takeIf { it > 0 }?.let { NativeCore.cancelReconstruction(it) }
                        throw paused
                    }
                    leaseMutex.withLock {
                        val renewed = jobs.renew(lease, System.currentTimeMillis(), LEASE_MILLIS)
                        lease = renewed.lease
                        mutableCurrent.value = renewed.snapshot
                    }
                }
            }
            checkResources()
            repository.withProjectAccess(access) {
                if (repository.dao.project(input.projectId)?.revision != input.projectRevision)
                    throw DiagnosticOperationException(ScanFailure(ScanFailureCode.REVISION_MISMATCH, ScanStage.IMPORT,
                        StageOutcome.FAILED, RecoveryAction.REVIEW_INPUTS, "scan.failure.revision_mismatch"))
                val currentInputs = repository.inputSnapshot(input.projectId)
                if (com.realityforge.scanner.data.InputPhotoIdentity.snapshotSha256(currentInputs) != input.inputSha256)
                    throw DiagnosticOperationException(ScanFailure(ScanFailureCode.INPUT_CHANGED, ScanStage.IMPORT,
                        StageOutcome.FAILED, RecoveryAction.REVIEW_INPUTS, "scan.failure.input_changed"))
                val calibration = LensCalibration.file(repository, input.projectId)
                if (ProductionPipelineSnapshot.calibrationSha256(calibration) != input.calibrationSha256)
                    throw DiagnosticOperationException(ScanFailure(ScanFailureCode.INPUT_CHANGED, ScanStage.CALIBRATION,
                        StageOutcome.FAILED, RecoveryAction.RECALIBRATE, "scan.failure.calibration_changed"))
                if (acquired.snapshot.cursor.acceptedStage != ReconstructionStage.CREATED) {
                    val decision = PipelineJobCheckpointValidator(repository).validate(acquired.snapshot)
                    // Each new attempt receives its own immutable binding. The verified source
                    // remains available to the stage adapters; their rebound commits are replayed
                    // in order and counters may restart within this new attempt.
                    leaseMutex.withLock {
                        val reset = jobs.rewindCheckpoint(lease, JobStageCursor.initial(),
                            if (decision is CheckpointRecoveryDecision.ResumeFrom) "CHECKPOINT_REBIND_NEW_ATTEMPT"
                            else decision.code, System.currentTimeMillis())
                        lease = reset.lease
                        mutableCurrent.value = reset.snapshot
                    }
                }
                repository.dao.state(input.projectId, input.projectRevision, "PROCESSING", "Processing local photographs; previous completed results remain available.")
                token = NativeCore.beginReconstruction()
                check(nativeToken.compareAndSet(0L, token))
                val processingPipeline = SparsePipeline(repository, selection,
                    com.realityforge.scanner.engine.artifacts.ArtifactBinding(input.projectId, input.projectRevision,
                        acquired.snapshot.jobId.value, acquired.snapshot.attempt),
                    object : com.realityforge.scanner.engine.pipeline.PipelineCheckpointSink {
                    override suspend fun started(stage: ReconstructionStage) {
                        ensureActive()
                        checkResources()
                        leaseMutex.withLock {
                            val measured = jobs.recordProgress(lease,
                                JobProgress(stage, 0, 1, JobProgressUnit.STAGES), System.currentTimeMillis())
                            lease = measured.lease
                            mutableCurrent.value = measured.snapshot
                        }
                    }
                    override suspend fun accepted(cursor: JobStageCursor) {
                        ensureActive()
                        checkResources()
                        leaseMutex.withLock {
                            val accepted = jobs.acceptStage(lease, cursor, System.currentTimeMillis())
                            lease = accepted.lease
                            val measured = jobs.recordProgress(lease,
                                JobProgress(cursor.acceptedStage, 1, 1, JobProgressUnit.STAGES), System.currentTimeMillis())
                            lease = measured.lease
                            mutableCurrent.value = measured.snapshot
                        }
                    }
                    }).apply {
                        diagnosticRecorder = diagnostics
                        onDiagnosticProgress = { measured ->
                            ensureActive()
                            check(measured.scope.jobId == acquired.snapshot.jobId.value &&
                                measured.scope.attempt.toLong() == acquired.snapshot.attempt &&
                                measured.scope.sourceRevision == input.projectRevision)
                            mutableDiagnosticProgress.value = measured
                        }
                    }
                executingPipeline = processingPipeline
                val result = processingPipeline.run(input.projectId, settings.getDouble("focal35"), token) { update ->
                    ensureActive()
                    checkResources()
                    mutableProgress.value = ExecutionProgress(acquired.snapshot.jobId.value, input.projectId, acquired.snapshot.attempt, update)
                }
                ensureActive()
                val key = result.getString("artifactKey")
                val report = repository.artifact(input.projectId, key, "json")
                val declarations = linkedMapOf(
                    "preview" to ("json" to "diagnostic-report"),
                    "sparse-glb" to ("glb" to "sparse-points"),
                    "sparse-ply" to ("ply" to "sparse-points"),
                )
                if (result.optString("denseStatus") == "ready") {
                    declarations["dense-glb"] = "dense.glb" to "dense-points"
                    declarations["dense-ply"] = "dense.ply" to "dense-points"
                }
                if (result.optString("meshStatus") == "ready") {
                    declarations["mesh-glb"] = "mesh.glb" to "observed-open-triangle-mesh"
                    declarations["mesh-ply"] = "mesh.ply" to "observed-open-triangle-mesh"
                }
                if (result.optString("textureStatus") == "ready") {
                    declarations["textured-glb"] = "textured.glb" to "photographic-textured-mesh"
                    declarations["texture-png"] = "texture.png" to "photographic-atlas"
                }
                val sources = declarations.mapValues { repository.artifact(input.projectId, key, it.value.first) }
                sources.values.forEach { check(it.isFile && it.length() > 0) { "A declared result artifact is missing." } }
                val additionalBytes = sources.values.fold(0L) { sum, file -> Math.addExact(sum, file.length()) }
                repository.storageBudget?.requireReservation(reservation, StorageEstimate(stagingBytes = additionalBytes))
                val publication = requireNotNull(repository.publications).beginResult(PublicationIdentity(
                    PublicationPurpose.RESULT_SET, input.projectId, input.projectRevision, input.requestId,
                    acquired.snapshot.attempt, input.inputSha256, input.settingsSha256, input.engineVersion),
                    declarations.map { (name, format) -> ArtifactDeclaration(name,
                        if (name == "preview") "preview.json" else "result.${format.first}", format.second,
                        required = true, maxBytes = requireNotNull(sources[name]).length()) })
                try {
                    var remaining = additionalBytes
                    sources.forEach { (name, file) ->
                        ensureActive()
                        repository.storageBudget?.requireReservation(reservation, StorageEstimate(stagingBytes = remaining))
                        publication.stageFile(name, file)
                        remaining -= file.length()
                    }
                    publication.seal()
                    // All files exist and are sealed. No lifecycle/resource interruption may split
                    // the directory promotion, project/result pointers, or durable completion.
                    heartbeat?.cancelAndJoin()
                    leaseMutex.withLock {
                        lease = jobs.renew(lease, System.currentTimeMillis(), LEASE_MILLIS).lease
                        withContext(NonCancellable) { publication.publish() }
                    }
                    completed = requireNotNull(jobs.snapshot(acquired.snapshot.jobId))
                    check(completed?.state == ReconstructionJobState.COMPLETED)
                } catch (failure: Throwable) {
                    runCatching { publication.cancel(failure.message?.take(300) ?: "Publication interrupted.") }
                    throw failure
                }
            }
            completed?.let { emit(it) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val persisted = jobs.snapshot(acquired.snapshot.jobId)
                if (persisted?.state == ReconstructionJobState.COMPLETED) { emit(persisted); return@withContext }
                val resourcePause = resourceFailure.get()
                if (resourcePause != null) {
                    val failure = recordFailure(resourcePause)
                    val paused = jobs.pause(acquired.snapshot.jobId, System.currentTimeMillis(),
                        failure?.code?.wireName ?: "RESOURCE_PAUSED").snapshot
                    emit(paused)
                } else {
                    diagnostics?.outcome(executingPipeline?.activeDiagnosticStage ?: ScanStage.STORAGE,
                        if (persisted?.state == ReconstructionJobState.CANCELLED) StageOutcome.CANCELLED else StageOutcome.PAUSED)
                }
                leaseMutex.withLock {
                    runCatching { jobs.relinquish(lease, "PROCESSING_INTERRUPTED", System.currentTimeMillis()) }
                        .getOrNull()?.let { emit(it) }
                }
                repository.dao.state(input.projectId, input.projectRevision, "INTERRUPTED", "Processing paused. Originals, checkpoints and the last completed result are safe.")
            }
            throw cancelled
        } catch (paused: ResourceCheckpointPause) {
            withContext(NonCancellable) {
                val failure = recordFailure(paused)
                emit(jobs.pause(acquired.snapshot.jobId, System.currentTimeMillis(),
                    failure?.code?.wireName ?: "RESOURCE_PAUSED").snapshot)
                repository.dao.state(input.projectId, input.projectRevision, "INTERRUPTED", paused.message.orEmpty().take(500))
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val persisted = jobs.snapshot(acquired.snapshot.jobId)
                if (persisted?.state == ReconstructionJobState.COMPLETED) { emit(persisted); return@withContext }
                val typedFailure = recordFailure(failure)
                val paused = typedFailure?.outcome == StageOutcome.PAUSED
                leaseMutex.withLock {
                    runCatching {
                        if (paused) jobs.pause(acquired.snapshot.jobId, System.currentTimeMillis(), typedFailure?.code?.wireName).snapshot
                        else jobs.fail(lease, typedFailure?.code?.wireName ?: NativeBoundaryFailure.from(failure).name, System.currentTimeMillis())
                    }.getOrNull()?.let { emit(it) }
                }
                repository.dao.state(input.projectId, input.projectRevision, if (paused) "INTERRUPTED" else "FAILED",
                    failure.message.orEmpty().take(500))
            }
        } finally {
            withContext(NonCancellable) { repository.storageBudget?.release(reservation) }
            heartbeat?.cancelAndJoin()
            if (token > 0) {
                nativeToken.compareAndSet(token, 0L)
                NativeCore.finishReconstruction(token)
            }
            mutableProgress.value = null
            mutableDiagnosticProgress.value = null
            diagnostics?.close()
            runningJobId.compareAndSet(acquired.snapshot.jobId, null)
        }
    }

    /** A stale screen cannot pause/cancel/retry a replacement attempt or project revision. */
    suspend fun dispatchUiAction(action: com.realityforge.scanner.ui.processing.JobAction): Boolean {
        awaitRecovery()
        val id = action.identity
        val command = when (action.command) {
            com.realityforge.scanner.ui.processing.JobCommand.PAUSE -> FencedJobCommand.PAUSE
            com.realityforge.scanner.ui.processing.JobCommand.RESUME -> FencedJobCommand.RESUME
            com.realityforge.scanner.ui.processing.JobCommand.RETRY -> FencedJobCommand.RETRY
            com.realityforge.scanner.ui.processing.JobCommand.CANCEL -> FencedJobCommand.CANCEL
            else -> return false
        }
        // Tokens are never recycled. Signal this exact operation, not a later worker.
        val token = if (runningJobId.get()?.value == id.jobId && runningAttempt.get() == id.attempt)
            nativeToken.get() else 0L
        val result = database.withTransaction {
            if (repository.dao.project(id.projectId)?.revision != id.revision) return@withTransaction null
            jobs.commandIfCurrent(JobId(id.jobId), id.projectId, id.revision,
                id.attempt, id.stateVersion, command, System.currentTimeMillis())
        } ?: return false
        mutableCurrent.value = result.snapshot
        if (command == FencedJobCommand.PAUSE || command == FencedJobCommand.CANCEL) {
            if (token > 0) {
                try { NativeCore.cancelReconstruction(token) }
                catch (_: IllegalArgumentException) { /* Exact operation already finished. */ }
                catch (_: IllegalStateException) { /* Exact operation already finished. */ }
            }
        } else {
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, ReconstructionService::class.java).setAction(ReconstructionService.ACTION_START))
            } catch (_: RuntimeException) {
                mutableCurrent.value = jobs.pause(result.snapshot.jobId, System.currentTimeMillis(),
                    "FOREGROUND_START_BLOCKED").snapshot
                return false
            }
        }
        return true
    }

    suspend fun pause(jobId: JobId) {
        val snapshot = jobs.pause(jobId, System.currentTimeMillis()).snapshot
        if (currentJobId() == jobId || runningJobId.get() == null) mutableCurrent.value = snapshot
        if (runningJobId.get() == jobId) stopNative()
    }
    suspend fun cancel(jobId: JobId) {
        val snapshot = jobs.cancel(jobId, System.currentTimeMillis()).snapshot
        if (currentJobId() == jobId || runningJobId.get() == null) mutableCurrent.value = snapshot
        if (runningJobId.get() == jobId) stopNative()
    }
    suspend fun resume(jobId: JobId) {
        awaitRecovery()
        val snapshot = requireNotNull(jobs.snapshot(jobId))
        mutableCurrent.value = if (snapshot.state == ReconstructionJobState.FAILED)
            jobs.retry(jobId, System.currentTimeMillis()).snapshot else jobs.resume(jobId, System.currentTimeMillis()).snapshot
        ContextCompat.startForegroundService(context, Intent(context, ReconstructionService::class.java).setAction(ReconstructionService.ACTION_START))
    }
    fun interrupt() {
        val current = mutableCurrent.value
        stopNative()
        if (current?.state == ReconstructionJobState.RUNNING) scope.launch {
            runCatching {
                val paused = jobs.pause(current.jobId, System.currentTimeMillis(), "PROCESSING_INTERRUPTED").snapshot
                if (runningJobId.get() == current.jobId || runningJobId.get() == null) mutableCurrent.value = paused
            }
        }
    }
    private fun stopNative() { nativeToken.get().takeIf { it > 0 }?.let { NativeCore.cancelReconstruction(it) }; worker?.cancel() }
    private fun currentJobId() = mutableCurrent.value?.jobId

    companion object {
        const val ENGINE_VERSION = "m19-integrated-runner-v2"
        private const val LEASE_MILLIS = 45_000L
    }
}

data class ExecutionProgress(val jobId: String, val projectId: String, val attempt: Long, val progress: SparsePipeline.Progress)
