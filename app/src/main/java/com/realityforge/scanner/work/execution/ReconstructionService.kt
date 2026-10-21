package com.realityforge.scanner.work.execution

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.realityforge.scanner.R
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.data.jobs.JobId
import com.realityforge.scanner.data.jobs.JobSnapshot
import com.realityforge.scanner.processing.notifications.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User-initiated local photo processing; no boot receiver or force-stop restart promise. */
class ReconstructionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as RealityForgeApplication).compositionRoot.execution
    private val commandMutex = Mutex()
    private val drain = ProcessingServiceDrain()
    private val foregroundGate = NotificationUpdateGate()
    private val shownIdentities = mutableMapOf<String, ProcessingJobIdentity>()
    private lateinit var notifications: AndroidProcessingNotificationController
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        notifications = processingNotificationController(this)
        notifications.ensureChannels()
        scope.launch {
            controller.awaitRecovery()
            // Room progress and commands remain authoritative even when the worker callback is
            // being cancelled. A second START never creates a second observer or worker binding.
            controller.current.map { it?.jobId }.distinctUntilChanged().collectLatest { jobId ->
                if (jobId != null) controller.jobs.observe(jobId).collect { snapshot ->
                    snapshot?.let(::render)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        drain.commandReceived(startId)
        if (!foreground) promote(initialNotification()) // Before Room or native work suspends.
        scope.launch {
            commandMutex.withLock {
                try {
                    controller.awaitRecovery()
                    if (intent?.action == ACTION_START) {
                        drain.requestStart()
                        startWorkerIfNeeded()
                    } else {
                        val decoded = intent?.let { JobNotificationPendingIntentFactory.decode(it, packageName,
                            ComponentName(this@ReconstructionService, ReconstructionService::class.java)) }
                        val command = (decoded as? JobActionDecodeResult.Decoded)?.action
                        val jobId = command?.let { runCatching { JobId(it.identity.jobId) }.getOrNull() }
                        if (command != null && jobId != null && controller.dispatchNotificationAction(command)) {
                            if (command.type == JobNotificationActionType.RESUME) {
                                drain.requestStart()
                                startWorkerIfNeeded()
                            }
                            controller.jobs.snapshot(jobId)?.let(::render)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    // Rejected/stale commands cannot stop an unrelated live worker. Durable state
                    // remains visible in-app; a later explicit action can retry an idle failure.
                    drain.abandonUnboundStart()
                } finally {
                    drain.commandFinished()
                    finishIfIdle()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startWorkerIfNeeded() {
        if (drain.workerBound || !drain.startRequested) return
        // A previous service instance may have cancelled a worker whose native cleanup is still
        // unwinding. Binding before its Job completes would hit start()'s duplicate-start guard.
        controller.awaitWorkerStopped()
        if (!drain.claimWorker()) return
        try {
            controller.start(onUpdate = { snapshot ->
                withContext(Dispatchers.Main.immediate) { render(snapshot) }
            }, onIdle = {
                scope.launch {
                    // onIdle runs inside the worker's finally block. Join from this independent
                    // scope before another START is allowed to drain the queue.
                    controller.awaitWorkerStopped()
                    commandMutex.withLock {
                        drain.workerStopped()
                        startWorkerIfNeeded()
                        finishIfIdle()
                    }
                }
            })
        } catch (failure: RuntimeException) {
            drain.workerStopped()
            throw failure
        }
    }

    private fun render(snapshot: JobSnapshot) {
        val job = snapshot.toNotification()
        val previous = shownIdentities.put(job.identity.jobId, job.identity)
        if (previous != null && previous != job.identity) {
            notifications.remove(previous)
            foregroundGate.clear(previous)
        }
        if (job.status == JobNotificationStatus.RUNNING && drain.workerBound) {
            if (foregroundGate.evaluate(job, SystemClock.elapsedRealtime()) == NotificationUpdateDecision.PUBLISH)
                notifications.build(job)?.let { promote(it.notification) }
        } else if (job.status == JobNotificationStatus.PAUSED || job.status.isTerminal) {
            // Tagged paused/result notifications survive removal of the service's untagged FGS
            // notification. Posting permission is independent from the required FGS promotion.
            notifications.publish(job, AndroidNotificationPermissionFactsReader.read(this,
                hasRequestedRuntimePermission = false, shouldShowRationale = false))
        }
    }

    private fun finishIfIdle() {
        if (!drain.canStop) return
        removeForeground()
        stopSelfResult(drain.latestStartId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android grants only seconds here. Fence/cancel work and stop the service immediately.
        stopExecution()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopExecution()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopExecution() {
        controller.interrupt()
        scope.cancel()
        removeForeground()
        stopSelf()
    }

    override fun onDestroy() {
        if (drain.workerBound) controller.interrupt()
        scope.cancel()
        removeForeground()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(FOREGROUND_ID, notification, type)
        foreground = true
    }

    private fun removeForeground() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun initialNotification(): Notification {
        val channel = NotificationChannelKind.PROCESSING.channelId
        val public = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.a029_public_title))
            .setContentText(getString(R.string.a029_public_body)).build()
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.a029_notification_running_title))
            .setContentText(getString(R.string.a029_stage_preparing))
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }

    companion object {
        const val ACTION_START = "com.realityforge.scanner.START_RECONSTRUCTION"
        private const val FOREGROUND_ID = 0xA008
    }
}
