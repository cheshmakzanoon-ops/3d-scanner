package com.realityforge.scanner.ui.storage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.realityforge.scanner.R
import com.realityforge.scanner.data.cachegc.*
import com.realityforge.scanner.data.storagebudget.SpaceDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class StorageScreenState(
    val inventory: CacheInventory? = null,
    val plan: CleanupPlan? = null,
    val measuredAvailableBytes: Long? = null,
    val measuring: Boolean = true,
    val cleaning: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val error: String? = null,
    val reclaimedBytes: Long? = null,
    val budgetFailure: SpaceDecision? = null,
    val projectBusy: Boolean = false,
) {
    val canClean: Boolean get() = !measuring && !cleaning && !projectBusy &&
        inventory != null && plan != null && plan.targets.isNotEmpty() &&
        plan.projectId == inventory.projectId && plan.projectRevision == inventory.projectRevision &&
        plan.manifestId == inventory.manifestId && plan.rootIdentity == inventory.rootIdentity &&
        plan.referenceGeneration == inventory.referenceGeneration &&
        plan.decisions.none { it.reason == CleanupReason.KEEP_INVENTORY_INCOMPLETE }
}

enum class StorageAction { REFRESH, REVIEW_CLEANUP, CONFIRM_CLEANUP, DISMISS_CLEANUP, CANCEL, BACK }

@Composable
fun StorageScreen(state: StorageScreenState, onAction: (StorageAction) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("storage_screen"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.a028_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.a028_originals_safe), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedButton(onClick = { onAction(StorageAction.BACK) }) { Text(stringResource(R.string.a028_back)) }
            Text(if (state.measuredAvailableBytes == null) stringResource(R.string.a028_available_unknown)
                else stringResource(R.string.a028_available, state.measuredAvailableBytes.toString()))
        }
        if (state.measuring) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.a028_measuring))
        }
        state.inventory?.let { inventory ->
            items(CacheArtifactCategory.entries, key = { it.name }) { category ->
                ListItem(headlineContent = { Text(stringResource(categoryLabel(category))) },
                    supportingContent = { Text(stringResource(R.string.a028_byte_count,
                        (inventory.bytesByCategory[category] ?: 0L).toString())) })
            }
            item { Text(stringResource(if (inventory.issues.isEmpty()) R.string.a028_total else R.string.a028_partial_total,
                inventory.totalBytes.toString())) }
            if (inventory.issues.isNotEmpty()) item {
                Text(stringResource(R.string.a028_inventory_issues, inventory.issues.size),
                    color = MaterialTheme.colorScheme.error)
            }
        }
        state.budgetFailure?.let { budget -> item {
            Text(stringResource(R.string.a028_budget_failure, budget.requiredBytes.toString(),
                budget.measuredAvailableBytes?.toString() ?: stringResource(R.string.a028_unknown)),
                color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.a028_budget_reserved, budget.otherReservedBytes.toString(), budget.safetyBytes.toString()))
            Text(stringResource(R.string.a028_budget_retry), style = MaterialTheme.typography.bodySmall)
        } }
        state.plan?.let { plan ->
            item {
                Text(stringResource(R.string.a028_reclaimable, plan.reclaimableBytes.toString(), plan.targets.size))
                Text(stringResource(R.string.a028_recompute), style = MaterialTheme.typography.bodySmall)
            }
            // Bounded category/reason summaries retain the complete destructive scope without thousands of rows.
            val groups = plan.decisions.filter { it.action == CleanupAction.DELETE }.groupBy { it.category }
            items(groups.keys.toList(), key = { "delete_${it.name}" }) { category ->
                val entries = requireNotNull(groups[category])
                Text(stringResource(R.string.a028_cleanup_category, stringResource(categoryLabel(category)),
                    entries.size, entries.sumOf { it.bytes }.toString()))
            }
        }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        state.reclaimedBytes?.let { bytes -> item { Text(stringResource(R.string.a028_reclaimed, bytes.toString())) } }
        if (state.projectBusy) item { Text(stringResource(R.string.a028_busy)) }
        item {
            if (state.cleaning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.a028_cleaning))
                OutlinedButton(onClick = { onAction(StorageAction.CANCEL) }) { Text(stringResource(R.string.a028_cancel)) }
            } else {
                Button(onClick = { onAction(StorageAction.REVIEW_CLEANUP) }, enabled = state.canClean,
                    modifier = Modifier.testTag("storage_review_cleanup")) { Text(stringResource(R.string.a028_review)) }
                OutlinedButton(onClick = { onAction(StorageAction.REFRESH) }, enabled = !state.measuring) {
                    Text(stringResource(R.string.a028_refresh))
                }
            }
        }
    }
    if (state.requiresConfirmation && state.canClean) AlertDialog(
        onDismissRequest = { onAction(StorageAction.DISMISS_CLEANUP) },
        title = { Text(stringResource(R.string.a028_confirmation_title)) },
        text = { Text(stringResource(R.string.a028_confirmation_body, requireNotNull(state.plan).targets.size,
            state.plan.reclaimableBytes.toString())) },
        confirmButton = { TextButton(onClick = { onAction(StorageAction.CONFIRM_CLEANUP) }) { Text(stringResource(R.string.a028_confirm)) } },
        dismissButton = { TextButton(onClick = { onAction(StorageAction.DISMISS_CLEANUP) }) { Text(stringResource(R.string.a028_cancel)) } },
    )
}

/** Route effects are scoped to this project and composition; old measurements cannot populate a new route. */
@Composable
fun StorageRoute(
    projectId: String,
    projectInventory: suspend (String) -> Pair<CacheInventory, CleanupPlan>,
    cleanup: suspend (String, CleanupPlan, CacheCleanupCancellation) -> CleanupExecutionResult,
    availableBytes: () -> Long?,
    onBack: () -> Unit,
    projectBusy: Boolean = false,
    budgetFailure: SpaceDecision? = null,
) {
    var state by remember(projectId) { mutableStateOf(StorageScreenState()) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cancel = remember(projectId) { AtomicBoolean(false) }
    var active by remember(projectId) { mutableStateOf<Job?>(null) }
    val refresh: () -> Unit = {
        active?.cancel()
        active = scope.launch {
            state = state.copy(measuring = true, requiresConfirmation = false)
            try {
                val result = withContext(Dispatchers.IO) { projectInventory(projectId) to availableBytes() }
                ensureActive()
                check(result.first.first.projectId == projectId && result.first.second.projectId == projectId)
                state = state.copy(inventory = result.first.first, plan = result.first.second,
                    measuredAvailableBytes = result.second, measuring = false)
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
            } catch (failure: Exception) { state = state.copy(measuring = false, error = failure.message?.take(300)) }
        }
    }
    DisposableEffect(projectId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> refresh()
                Lifecycle.Event.ON_STOP -> {
                    cancel.set(true)
                    active?.cancel()
                    state = state.copy(measuring = true, cleaning = false, requiresConfirmation = false, plan = null)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel.set(true); active?.cancel() }
    }
    LaunchedEffect(projectBusy) {
        if (!projectBusy && !state.measuring && !state.cleaning && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refresh()
    }
    StorageScreen(state = state.copy(projectBusy = projectBusy, budgetFailure = budgetFailure), onAction = { action ->
        when (action) {
            StorageAction.BACK -> { cancel.set(true); onBack() }
            StorageAction.REFRESH -> { state = state.copy(error = null); refresh() }
            StorageAction.REVIEW_CLEANUP -> state = state.copy(requiresConfirmation = true)
            StorageAction.DISMISS_CLEANUP -> state = state.copy(requiresConfirmation = false)
            StorageAction.CANCEL -> cancel.set(true)
            StorageAction.CONFIRM_CLEANUP -> if (state.copy(projectBusy = projectBusy).canClean) {
                val approved = requireNotNull(state.plan)
                cancel.set(false)
                active = scope.launch {
                    state = state.copy(cleaning = true, requiresConfirmation = false, error = null, reclaimedBytes = null)
                    try {
                        val result = withContext(Dispatchers.IO) {
                            cleanup(projectId, approved, CacheCleanupCancellation { cancel.get() })
                        }
                        ensureActive()
                        state = state.copy(cleaning = false, reclaimedBytes = result.deletedBytes,
                            error = if (result.state in setOf(CleanupExecutionState.COMPLETED, CleanupExecutionState.NOTHING_TO_DO)) null
                                else result.state.name.lowercase().replace('_', ' '))
                        refresh()
                    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
                    } catch (failure: Exception) { state = state.copy(cleaning = false, error = failure.message?.take(300)) }
                }
            }
        }
    })
}

private fun categoryLabel(category: CacheArtifactCategory): Int = when (category) {
    CacheArtifactCategory.ORIGINAL -> R.string.a028_originals
    CacheArtifactCategory.FEATURE_CACHE -> R.string.a028_features
    CacheArtifactCategory.DEPTH_CHECKPOINT -> R.string.a028_depth
    CacheArtifactCategory.INTERMEDIATE -> R.string.a028_intermediate
    CacheArtifactCategory.RESULT -> R.string.a028_results
    CacheArtifactCategory.TEMPORARY -> R.string.a028_temporary
    CacheArtifactCategory.UNKNOWN -> R.string.a028_other
}
