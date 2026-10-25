package com.realityforge.scanner.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.export.*
import com.realityforge.scanner.engine.SurfaceTopologySummary
import com.realityforge.scanner.ui.*
import com.realityforge.scanner.ui.designsystem.LabelledToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat

private data class StudioPreview(
    val projectId: String, val revision: Long, val resultKey: String?,
    val geometry: com.realityforge.scanner.data.reopening.PreparedPreviewGeometry,
    val photographic: File?,
)

@Composable
fun StudioRoute(result: JSONObject?, model: ScannerViewModel, modifier: Modifier, onExport: () -> Unit) {
    val project by model.selectedProject.collectAsState()
    val selected by model.selectedId.collectAsState()
    var loaded by remember { mutableStateOf<StudioPreview?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var representation by rememberSaveable(selected, project?.resultKey) { mutableStateOf("sparse") }
    var wireframe by rememberSaveable { mutableStateOf(false) }
    var photographicOpen by rememberSaveable(selected, project?.resultKey) { mutableStateOf(false) }
    LaunchedEffect(selected, project?.revision, project?.resultKey, project?.resultRevision, project?.state) {
        loaded = null; failure = null
        val source = project?.takeIf { it.id == selected } ?: return@LaunchedEffect
        if (source.state in setOf("DELETING", "DELETING_PHOTO")) return@LaunchedEffect
        try {
            model.compositionRoot.execution.awaitRecovery()
            val prepared = withContext(Dispatchers.IO) {
                when (val reopened = model.repository.reopen(source.id, false)) {
                    is com.realityforge.scanner.data.reopening.ReopenResult.Ready -> {
                        val geometry = com.realityforge.scanner.data.reopening.preparePreviewGeometry(reopened.model)
                        val photo = if (com.realityforge.scanner.data.reopening.ScanRepresentation.PHOTOGRAPHIC_MESH !in geometry.capabilities) null
                        else if (reopened.publicationSetId == null) model.repository.artifact(source.id, geometry.artifactKey, "textured.glb")
                        else when (val publication = model.compositionRoot.publications.committed(source.id, reopened.publicationSetId)) {
                            is com.realityforge.scanner.data.publication.PublicationReadResult.Verified -> {
                                check(publication.value.manifest.identity.projectRevision == geometry.revision)
                                publication.value.artifact("textured-glb")
                            }
                            else -> error("The completed photographic model is unavailable.")
                        }
                        StudioPreview(source.id, source.revision, source.resultKey, geometry, photo)
                    }
                    is com.realityforge.scanner.data.reopening.ReopenResult.Invalid -> error(reopened.detail)
                    else -> null
                }
            }
            if (model.selectedId.value == source.id && model.selectedProject.value?.revision == source.revision &&
                model.selectedProject.value?.resultKey == source.resultKey) loaded = prepared
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "Cannot open completed model." }
    }
    val current = loaded?.takeIf { it.projectId == selected && it.revision == project?.revision && it.resultKey == project?.resultKey }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.a071_unknown_scale))
        if (current == null) Text(failure ?: stringResource(R.string.a071_no_result))
        else {
            val geometry = current.geometry
            if (geometry.mesh == null) Text(stringResource(R.string.a071_points_only))
            FilterChip(representation == "sparse", { representation = "sparse" }, label = { Text(stringResource(R.string.a071_sparse)) })
            if (geometry.dense != null) FilterChip(representation == "dense", { representation = "dense" }, label = { Text(stringResource(R.string.a071_dense)) })
            if (geometry.mesh != null) FilterChip(representation == "surface", { representation = "surface" }, label = { Text(stringResource(R.string.a071_surface)) })
            when (representation) {
                "dense" -> geometry.dense?.let { PointCloudViewer(it, Modifier.fillMaxWidth().height(440.dp)) }
                "surface" -> geometry.mesh?.let {
                    SurfaceMeshViewer(it, Modifier.fillMaxWidth().height(500.dp), wireframe)
                    LabelledToggle(stringResource(R.string.a071_wireframe), wireframe, { wireframe = it })
                }
                else -> PointCloudViewer(geometry.sparse, Modifier.fillMaxWidth().height(440.dp))
            }
            if (geometry.mesh != null) SurfaceTopologyQualification(geometry.surfaceTopology)
            if (current.photographic != null) Button(onClick = { photographicOpen = true },
                modifier = Modifier.fillMaxWidth().testTag("a071-open-photographic")) { Text(stringResource(R.string.a071_open_photographic)) }
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth().testTag("a071-open-export")) { Text(stringResource(R.string.a071_open_export)) }
            Text(stringResource(R.string.a071_evidence_limits))
            geometry.unavailableReasons.values.forEach { Text(it) }
        }
    }
    if (photographicOpen) current?.photographic?.let { file -> PhotographicModelViewer(file) { photographicOpen = false } }
}

private data class ExportCatalog(val selection: SurfaceExportSelection,
    val identity:ArtifactIdentity?=null,val options:List<ExportOption> = emptyList(),
    val topology: SurfaceTopologyExportEvidence? = null, val loading:Boolean=true,val failed:Boolean=false)
@Composable
fun ExportRoute(projectId:String,source:RepositoryExportSource,modifier:Modifier,busy:Boolean,
    expectedRevision: Long?, expectedArtifactKey: String?,
    onExport:(ArtifactIdentity,ExportOption)->Unit,onArchive:()->Unit) {
    val selection = SurfaceExportSelection(projectId, expectedRevision, expectedArtifactKey)
    val loadedCatalog by produceState(ExportCatalog(selection),selection,source,busy) {
        value = ExportCatalog(selection)
        value = withContext(Dispatchers.IO) {
            try {
                val identity=source.snapshot(projectId)
                if (identity == null || !selection.matches(identity)) ExportCatalog(selection, loading=false)
                else {
                    val options = source.available(identity)
                    // Qualification is advisory: a failed read must not remove otherwise valid export choices.
                    val topology = if (options.any { it.isSurfaceMesh() }) try {
                        source.surfaceTopology(identity)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (_: Exception) { null }
                    else null
                    if (source.isCurrent(identity)) ExportCatalog(selection, identity, options, topology, loading=false)
                    else ExportCatalog(selection, loading=false)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { ExportCatalog(selection,loading=false,failed=true) }
        }
    }
    // produceState retains its old value until the new effect runs. Fence display immediately too.
    val catalog = loadedCatalog.takeIf { it.selection == selection &&
        (it.identity == null || selection.matches(it.identity)) } ?: ExportCatalog(selection)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.a071_export_help))
        if(catalog.loading) CircularProgressIndicator()
        if(catalog.failed) Text(stringResource(R.string.a071_error),color=MaterialTheme.colorScheme.error)
        if(!catalog.loading && catalog.options.isEmpty()) Text(stringResource(R.string.a071_no_result))
        if (catalog.options.any { it.isSurfaceMesh() }) {
            SurfaceTopologyQualification(catalog.identity?.let { catalog.topology?.forArtifact(it) } ?: SurfaceTopologySummary())
        }
        catalog.options.forEach { option ->
            val label=stringResource(when(option.representation) {
                AssetRepresentation.SPARSE_POINTS->R.string.a071_sparse
                AssetRepresentation.DENSE_POINTS->R.string.a071_dense
                AssetRepresentation.OBSERVED_SURFACE->R.string.a071_surface
                AssetRepresentation.PHOTOGRAPHIC_SURFACE->R.string.a071_photographic
                AssetRepresentation.PHOTOGRAPHIC_ATLAS->R.string.a071_atlas
                else->R.string.a071_diagnostics
            })
            OutlinedButton(enabled=!busy,onClick={catalog.identity?.let {onExport(it,option)}},modifier=Modifier.fillMaxWidth().testTag("a071-export-${option.id}")) {
                Text(stringResource(R.string.a071_export_option,label,option.format.name,NumberFormat.getIntegerInstance().format(option.bytes)))
            }
        }
        OutlinedButton(enabled=!busy,onClick=onArchive,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.a071_export_archive))}
    }
}
