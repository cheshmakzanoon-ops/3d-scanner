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
import com.realityforge.scanner.ui.*
import com.realityforge.scanner.ui.designsystem.LabelledToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat

@Composable
fun StudioRoute(result:JSONObject?,model:ScannerViewModel,modifier:Modifier,onExport:()->Unit) {
    var representation by rememberSaveable(result?.optString("artifactKey")) { mutableStateOf("sparse") }
    var wireframe by rememberSaveable { mutableStateOf(false) }
    var photographicFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(result?.optString("artifactKey")) { photographicFile=null }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(result==null) Text(stringResource(R.string.a071_no_result)) else {
            Text(stringResource(R.string.a071_unknown_scale))
            val dense=result.optJSONObject("densePreview")
            val mesh=result.optJSONObject("meshPreview")
            val texture=result.optJSONObject("texturePreview")
            // Buttons wrap vertically and remain keyboard/TalkBack operable at large fonts.
            FilterChip(representation=="sparse",{representation="sparse"},label={Text(stringResource(R.string.a071_sparse))})
            if(dense!=null) FilterChip(representation=="dense",{representation="dense"},label={Text(stringResource(R.string.a071_dense))})
            if(mesh!=null) FilterChip(representation=="surface",{representation="surface"},label={Text(stringResource(R.string.a071_surface))})
            when(representation) {
                "dense" -> if(dense!=null) PointCloudViewer(dense,Modifier.fillMaxWidth().height(320.dp))
                "surface" -> if(mesh!=null) {
                    SurfaceMeshViewer(mesh,Modifier.fillMaxWidth().height(320.dp),wireframe)
                    LabelledToggle(stringResource(R.string.a071_wireframe),wireframe,{wireframe=it})
                }
                else -> PointCloudViewer(result,Modifier.fillMaxWidth().height(320.dp))
            }
            if(texture!=null) Button(onClick={
                val id=model.selectedId.value
                if(id!=null) runCatching {model.repository.artifact(id,result.getString("artifactKey"),"textured.glb")}
                    .onSuccess {photographicFile=it}.onFailure {model.notice.value=it.message}
            },modifier=Modifier.fillMaxWidth().testTag("a071-open-photographic")) {Text(stringResource(R.string.a071_open_photographic))}
            Button(onClick=onExport,modifier=Modifier.fillMaxWidth().testTag("a071-open-export")) {Text(stringResource(R.string.a071_open_export))}
            val evidence=result.optJSONObject("scanDiagnostics")
            if(evidence!=null) {
                Text(stringResource(R.string.a071_evidence),Modifier.semantics {heading()},style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.a071_evidence_counts,evidence.optInt("registeredCameraCount"),evidence.optInt("importedPhotoCount"),evidence.optInt("observedMeshTriangles")))
                Text(stringResource(R.string.a071_evidence_limits))
            }
        }
    }
    photographicFile?.let { file -> PhotographicModelViewer(file) {photographicFile=null} }
}

private data class ExportCatalog(val identity:ArtifactIdentity?=null,val options:List<ExportOption> = emptyList(),val loading:Boolean=true,val failed:Boolean=false)
@Composable
fun ExportRoute(projectId:String,source:RepositoryExportSource,modifier:Modifier,busy:Boolean,
    onExport:(ArtifactIdentity,ExportOption)->Unit,onArchive:()->Unit) {
    val catalog by produceState(ExportCatalog(),projectId,source,busy) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                val identity=source.snapshot(projectId)
                ExportCatalog(identity,if(identity==null) emptyList() else source.available(identity),loading=false)
            }.getOrElse {ExportCatalog(loading=false,failed=true)}
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.a071_export_help))
        if(catalog.loading) CircularProgressIndicator()
        if(catalog.failed) Text(stringResource(R.string.a071_error),color=MaterialTheme.colorScheme.error)
        if(!catalog.loading && catalog.options.isEmpty()) Text(stringResource(R.string.a071_no_result))
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
