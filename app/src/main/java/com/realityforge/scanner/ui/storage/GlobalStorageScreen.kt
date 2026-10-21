package com.realityforge.scanner.ui.storage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.realityforge.scanner.R
import com.realityforge.scanner.data.cachegc.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Paged measured summaries. Cleanup remains a separately reviewed per-project command. */
@Composable
fun GlobalStorageScreen(service: GlobalCacheInventoryService, onProject: (String) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember(service) { mutableStateOf<GlobalStoragePage?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Job?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(service, offset, refresh, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            running = currentCoroutineContext().job
            measuring = true
            stopped = false
            failed = false
            // A return from the background measures fresh references under fresh project leases.
            page = null
            try { page = service.measurePage(offset) { page = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { measuring = false; running = null }
        }
    }
    fun close() { running?.cancel(); onBack() }
    BackHandler { close() }
    LazyColumn(modifier.fillMaxSize().testTag("global_storage_screen"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.a020g_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.a020g_scope))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { close() }) { Text(stringResource(R.string.a020g_back)) }
                OutlinedButton(enabled = !measuring, onClick = { refresh++ }) { Text(stringResource(R.string.a028_refresh)) }
            }
        }
        if (measuring) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.a020g_progress, page?.finishedProjectCount ?: 0, page?.projects?.size ?: 0))
            OutlinedButton(onClick = { stopped = true; running?.cancel() }) { Text(stringResource(R.string.a028_cancel)) }
        }
        if (stopped) item { Text(stringResource(R.string.a020g_stopped)) }
        if (failed) item { Text(stringResource(R.string.a020g_failed), color = MaterialTheme.colorScheme.error) }
        page?.let { measured ->
            item {
                Text(stringResource(R.string.a020g_subtotal, measured.measuredSubtotalBytes?.toString()
                    ?: stringResource(R.string.a028_unknown), measured.measuredProjectCount, measured.projects.size))
                Text(stringResource(R.string.a020g_reclaimable, measured.reclaimableSubtotalBytes?.toString()
                    ?: stringResource(R.string.a028_unknown)))
                Text(stringResource(R.string.a020g_complete, measured.completeProjectCount, measured.projects.size),
                    style = MaterialTheme.typography.bodySmall)
            }
            items(CacheArtifactCategory.entries, key = { "category_${it.name}" }) { category ->
                Text(stringResource(R.string.a020g_category, stringResource(globalCategoryLabel(category)),
                    measured.measuredCategoryBytes(category)?.toString() ?: stringResource(R.string.a028_unknown)))
            }
            if (measured.projects.isEmpty()) item { Text(stringResource(R.string.a020g_empty)) }
            items(measured.projects, key = { it.project.id }) { project ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(project.project.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(globalMeasurementLabel(project.measurement)))
                        project.measuredBytes?.let { bytes -> Text(stringResource(R.string.a020g_project_bytes, bytes.toString())) }
                        project.reclaimableBytes?.let { bytes -> Text(stringResource(R.string.a020g_project_reclaimable, bytes.toString())) }
                        if (project.issueCount > 0) Text(stringResource(R.string.a028_inventory_issues, project.issueCount))
                        TextButton(onClick = { running?.cancel(); onProject(project.project.id) }) {
                            Text(stringResource(R.string.a020g_open_project))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !measuring && offset > 0,
                        onClick = { offset = (offset - measured.pageSize).coerceAtLeast(0) }) { Text(stringResource(R.string.a020g_previous)) }
                    OutlinedButton(enabled = !measuring && measured.hasNextPage,
                        onClick = { offset = Math.addExact(offset, measured.pageSize) }) { Text(stringResource(R.string.a020g_next)) }
                }
            }
        }
    }
}

private fun globalMeasurementLabel(value: ProjectStorageMeasurement): Int = when (value) {
    ProjectStorageMeasurement.PENDING -> R.string.a020g_pending
    ProjectStorageMeasurement.MEASURED -> R.string.a020g_measured
    ProjectStorageMeasurement.PARTIAL -> R.string.a020g_partial
    ProjectStorageMeasurement.BUSY -> R.string.a020g_busy
    ProjectStorageMeasurement.CHANGED -> R.string.a020g_changed
    ProjectStorageMeasurement.UNAVAILABLE -> R.string.a020g_unavailable
}

private fun globalCategoryLabel(value: CacheArtifactCategory): Int = when (value) {
    CacheArtifactCategory.ORIGINAL -> R.string.a028_originals
    CacheArtifactCategory.FEATURE_CACHE -> R.string.a028_features
    CacheArtifactCategory.DEPTH_CHECKPOINT -> R.string.a028_depth
    CacheArtifactCategory.INTERMEDIATE -> R.string.a028_intermediate
    CacheArtifactCategory.RESULT -> R.string.a028_results
    CacheArtifactCategory.TEMPORARY -> R.string.a028_temporary
    CacheArtifactCategory.UNKNOWN -> R.string.a028_other
}
