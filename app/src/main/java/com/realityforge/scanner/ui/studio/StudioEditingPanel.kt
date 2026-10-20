package com.realityforge.scanner.ui.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R
import com.realityforge.scanner.studio.editing.*

/** C11/C15 bridge: callbacks persist/materialize recipes; this UI never rewrites a master. */
@Composable
fun StudioEditingPanel(
    controller: EditController,
    onRecipeChanged: (EditRecipe) -> Unit,
    modifier: Modifier = Modifier,
) {
    var revision by remember(controller) { mutableIntStateOf(0) }
    var minimum by rememberSaveable { mutableStateOf("") }
    var maximum by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    var meters by rememberSaveable { mutableStateOf("") }
    var uncertainty by rememberSaveable { mutableStateOf("") }
    var referenceLabel by rememberSaveable { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    fun apply(operation: () -> EditOperation) {
        try { controller.apply(operation()) }
        catch (_: IllegalArgumentException) { rejected=true; return }
        revision++; rejected=false; onRecipeChanged(controller.recipe)
    }
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.a070_edit_explanation))
        key(revision) { Text(stringResource(R.string.a070_recipe_count,controller.recipe.operations.size)) }
        Row {
            TextButton(onClick={ if(controller.undo()) { revision++; onRecipeChanged(controller.recipe) } },enabled=controller.canUndo) { Text(stringResource(R.string.a070_undo)) }
            TextButton(onClick={ if(controller.redo()) { revision++; onRecipeChanged(controller.recipe) } },enabled=controller.canRedo) { Text(stringResource(R.string.a070_redo)) }
            TextButton(onClick={ apply { EditOperation.Transform(listOf(0.0,0.0,1.0,0.0, 0.0,1.0,0.0,0.0, -1.0,0.0,0.0,0.0, 0.0,0.0,0.0,1.0)) } }) {
                Text(stringResource(R.string.a070_rotate_edit))
            }
        }
        Text(stringResource(R.string.a070_source_coordinates))
        OutlinedTextField(minimum,{minimum=it},label={Text(stringResource(R.string.a070_crop_min))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(maximum,{maximum=it},label={Text(stringResource(R.string.a070_crop_max))},modifier=Modifier.fillMaxWidth())
        TextButton(onClick={apply { EditOperation.Crop(parseEditPoint(minimum),parseEditPoint(maximum)) }}) { Text(stringResource(R.string.a070_crop)) }
        OutlinedTextField(first,{first=it},label={Text(stringResource(R.string.a070_reference_start))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(second,{second=it},label={Text(stringResource(R.string.a070_reference_end))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(meters,{meters=it},label={Text(stringResource(R.string.a070_reference_distance))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(uncertainty,{uncertainty=it},label={Text(stringResource(R.string.a070_reference_uncertainty))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(referenceLabel,{referenceLabel=it},label={Text(stringResource(R.string.a070_reference_label))},modifier=Modifier.fillMaxWidth())
        TextButton(onClick={apply { EditOperation.MetricReference(parseEditPoint(first),parseEditPoint(second),
            requireNotNull(meters.toDoubleOrNull()),requireNotNull(uncertainty.toDoubleOrNull()),referenceLabel) }}) { Text(stringResource(R.string.a070_set_reference)) }
        if(rejected) Text(stringResource(R.string.a070_invalid_edit),color=MaterialTheme.colorScheme.error)
    }
}

/** Locale-independent engineering coordinate entry; presentation/measurements remain explicit. */
internal fun parseEditPoint(text: String): EditPoint {
    val parts=text.trim().split(Regex("[\\s;]+"))
    require(parts.size==3)
    return EditPoint(requireNotNull(parts[0].toDoubleOrNull()),requireNotNull(parts[1].toDoubleOrNull()),requireNotNull(parts[2].toDoubleOrNull()))
}
