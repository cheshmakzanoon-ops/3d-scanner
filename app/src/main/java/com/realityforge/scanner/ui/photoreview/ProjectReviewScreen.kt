package com.realityforge.scanner.ui.photoreview
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.realityforge.scanner.R
import com.realityforge.scanner.engine.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat

/** Display-only bounded decoding; source curation and reconstruction remain with the host. */
@Composable
fun ProjectReviewScreen(state:ProjectReviewUiState,onAction:(ReviewAction)->Unit,modifier:Modifier=Modifier, fallbackFocal35mm:Double=26.0) {
    var focal by rememberSaveable(state.projectId) { mutableStateOf(fallbackFocal35mm.toString()) }
    var inspectId by rememberSaveable(state.projectId) { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable(state.projectId) { mutableStateOf<String?>(null) }
    fun send(command:ReviewCommand) { val a=ReviewAction.Command(state.projectId,state.revision,command,if(command==ReviewCommand.RECONSTRUCT) focal.toDoubleOrNull() else null); if(ReviewAdmission.allowed(state,a)) onAction(a) }
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text(state.title,style=MaterialTheme.typography.headlineMedium)
            Text(pluralStringResource(R.plurals.a073_count,state.selectedCount,state.selectedCount,state.photos.size))
            if(state.selectedCount>state.maximumSelectedPhotos) Text(stringResource(R.string.a073_input_limit,state.maximumSelectedPhotos),color=MaterialTheme.colorScheme.error)
            Button(enabled=!state.busy,onClick={send(ReviewCommand.IMPORT)},modifier=Modifier.testTag("a073-import")) { Text(stringResource(R.string.a073_import)) }
            OutlinedButton(enabled=!state.busy,onClick={send(ReviewCommand.CAPTURE)},modifier=Modifier.testTag("a073-camera")) { Text(stringResource(R.string.a073_capture)) }
            state.calibrationStatus?.let { Text(it) }
            if(state.canCalibrate) { OutlinedButton(enabled=!state.busy,onClick={send(ReviewCommand.CALIBRATE)}) { Text(stringResource(R.string.a073_calibrate)) }; TextButton(enabled=!state.busy,onClick={send(ReviewCommand.CLEAR_CALIBRATION)}) { Text(stringResource(R.string.a073_clear_calibration)) } }
            OutlinedTextField(focal,{focal=it.take(8)},enabled=!state.busy,label={Text(stringResource(R.string.a073_focal))},supportingText={Text(stringResource(R.string.a073_focal_hint))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("a073-focal"))
            Button(enabled=state.canStart(focal.toDoubleOrNull()),onClick={send(ReviewCommand.RECONSTRUCT)},modifier=Modifier.fillMaxWidth().testTag("a073-reconstruct")) { Text(stringResource(R.string.a073_start)) }
            if(state.canInspectResult) OutlinedButton(enabled=!state.busy,onClick={send(ReviewCommand.OPEN_RESULT)},modifier=Modifier.testTag("a073-result")) { Text(stringResource(R.string.a073_result)) }
            TextButton(onClick={send(ReviewCommand.HELP)},enabled=!state.busy) { Text(stringResource(R.string.a073_help)) }
            if(state.canUndo) TextButton(enabled=!state.busy,onClick={send(ReviewCommand.UNDO)}) { Text(stringResource(R.string.a073_undo)) }
            state.diagnostics.take(32).forEach { Text(it) }
            state.evidence.filter { it.currentFor(state) }.take(32).forEach { diagnostic ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(diagnostic.message)
                        diagnostic.referencedPhotos(state).forEach { photo ->
                            TextButton(onClick={inspectId=photo.id}) { Text(photo.filename) }
                        }
                        if(diagnostic.recapture) OutlinedButton(enabled=!state.busy,onClick={send(ReviewCommand.CAPTURE)}) { Text(stringResource(R.string.a073_capture)) }
                    }
                }
            }
            if(state.canCurate) Text(stringResource(R.string.a073_originals),style=MaterialTheme.typography.bodySmall)
        }
        itemsIndexed(state.photos,key={_,p->p.id}) { index,photo ->
            Card(onClick={inspectId=photo.id},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    BoundedPhoto(photo.file,192,photo.filename,Modifier.size(112.dp))
                    Text(photo.filename,style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.a073_dimensions,photo.width,photo.height))
                    val number=NumberFormat.getNumberInstance().apply { maximumFractionDigits=1 }
                    val percent=NumberFormat.getPercentInstance()
                    val unknown=stringResource(R.string.a073_unknown)
                    Text(stringResource(R.string.a073_sharpness,photo.sharpness?.let(number::format)?:unknown))
                    Text(stringResource(R.string.a073_exposure,photo.darkFraction?.let(percent::format)?:unknown,photo.brightFraction?.let(percent::format)?:unknown))
                    if(photo.calibrationCompatible!=true) Text(stringResource(if(photo.calibrationCompatible==false) R.string.a073_calibration_bad else R.string.a073_calibration_unknown))
                    if(state.canCurate) {
                        Row(Modifier.fillMaxWidth().toggleable(value=photo.included,enabled=!state.busy && !photo.pendingRemoval,role=Role.Checkbox,onValueChange={included->onAction(ReviewAction.Include(state.projectId,state.revision,photo.id,included))})) { Checkbox(photo.included,null,enabled=!state.busy && !photo.pendingRemoval); Text(stringResource(R.string.a073_selected)) }
                        OutlinedButton(enabled=!state.busy && index>0,onClick={onAction(ReviewAction.Move(state.projectId,state.revision,photo.id,-1))}) { Text(stringResource(R.string.a073_up)) }
                        OutlinedButton(enabled=!state.busy && index<state.photos.lastIndex,onClick={onAction(ReviewAction.Move(state.projectId,state.revision,photo.id,1))}) { Text(stringResource(R.string.a073_down)) }
                        if(state.canRemove && !photo.pendingRemoval) TextButton(enabled=!state.busy,onClick={removeId=photo.id}) { Text(stringResource(R.string.a073_remove)) }
                        if(photo.pendingRemoval) { Text(stringResource(R.string.a073_pending));if(state.canRemove) TextButton(enabled=!state.busy,onClick={onAction(ReviewAction.Restore(state.projectId,state.revision,photo.id))}) { Text(stringResource(R.string.a073_restore)) } }
                    }
                }
            }
        }
    }
    state.photo(inspectId.orEmpty())?.let { photo ->
        Dialog(onDismissRequest={inspectId=null}) {
            var zoom by rememberSaveable(photo.id) { mutableFloatStateOf(1f) }
            var panX by rememberSaveable(photo.id) { mutableFloatStateOf(0f) }
            var panY by rememberSaveable(photo.id) { mutableFloatStateOf(0f) }
            Surface { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(photo.filename)
                Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().pointerInput(photo.id) {
                    detectTransformGestures { _, drag, multiplier, _ ->
                        zoom=(zoom*multiplier).coerceIn(1f,4f)
                        if(zoom>1f) {
                            panX=(panX+drag.x*2/(size.width*(zoom-1))).coerceIn(-1f,1f)
                            panY=(panY+drag.y*2/(size.height*(zoom-1))).coerceIn(-1f,1f)
                        } else { panX=0f; panY=0f }
                    }
                }) { BoundedPhoto(photo.file,1600,photo.filename,Modifier.fillMaxSize().graphicsLayer { scaleX=zoom; scaleY=zoom; translationX=panX*size.width*(zoom-1)/2; translationY=panY*size.height*(zoom-1)/2 }) }
                val zoomLabel=stringResource(R.string.a073_zoom)
                Text(zoomLabel); Slider(zoom,{zoom=it},valueRange=1f..4f,modifier=Modifier.semantics { contentDescription=zoomLabel })
                if(zoom>1f) {
                    val horizontal=stringResource(R.string.a073_pan_horizontal); val vertical=stringResource(R.string.a073_pan_vertical)
                    Text(horizontal); Slider(panX,{panX=it},valueRange=-1f..1f,modifier=Modifier.semantics { contentDescription=horizontal })
                    Text(vertical); Slider(panY,{panY=it},valueRange=-1f..1f,modifier=Modifier.semantics { contentDescription=vertical })
                }
                TextButton(onClick={inspectId=null}) { Text(stringResource(R.string.a073_close)) }
            } }
        }
    }
    state.photo(removeId.orEmpty())?.let { photo -> AlertDialog(onDismissRequest={removeId=null},title={Text(stringResource(R.string.a073_remove_title,photo.filename))},text={Text(stringResource(R.string.a073_originals))},confirmButton={TextButton(enabled=!state.busy,onClick={onAction(ReviewAction.Remove(state.projectId,state.revision,photo.id));removeId=null}) { Text(stringResource(R.string.a073_remove)) }},dismissButton={TextButton(onClick={removeId=null}) { Text(stringResource(R.string.a073_cancel)) }}) }
}
@Composable
private fun BoundedPhoto(file:File?,edge:Int,description:String,modifier:Modifier) {
    var failed by remember(file,edge) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null,file,edge) {
        val owned = try {
            file?.let { ImageLoader.decode(com.realityforge.scanner.imaging.DecodeRequest(file = it, maxEdge = edge)) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        value = owned?.borrowBitmap()
        failed = value == null
        awaitDispose { owned?.close() }
    }
    val image=bitmap
    if(image!=null) Image(image.asImageBitmap(),description,modifier,contentScale=ContentScale.Fit)
    else Text(stringResource(if(failed) R.string.a073_load_failed else R.string.a073_loading),modifier)
    // The decoder owner is released when this lazy item or inspection dialog leaves composition.
}
