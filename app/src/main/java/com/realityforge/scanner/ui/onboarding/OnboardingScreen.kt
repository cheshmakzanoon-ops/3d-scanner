package com.realityforge.scanner.ui.onboarding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R

/** Can be skipped and revisited; preferences persistence belongs to the host. No runtime network. */
@Composable
fun OnboardingScreen(onComplete: ()->Unit,onSkip: ()->Unit,modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val index=CaptureGuide.clampPage(page)
    BackHandler { if(index>0) page=index-1 else onSkip() }
    val titles=listOf(R.string.a075_subject_title,R.string.a075_overlap_title,R.string.a075_optics_title,R.string.a075_review_title,R.string.a075_calibration_title,R.string.a075_limits_title,R.string.a075_privacy_title)
    val bodies=listOf(R.string.a075_subject,R.string.a075_overlap,R.string.a075_optics,R.string.a075_review,R.string.a075_calibration,R.string.a075_limits,R.string.a075_privacy)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.a075_title),style=MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.a075_page,index+1,CaptureGuide.steps.size))
        Text(stringResource(titles[index]),Modifier.semantics { heading(); liveRegion=LiveRegionMode.Polite },style=MaterialTheme.typography.titleLarge)
        Text(stringResource(bodies[index]),style=MaterialTheme.typography.bodyLarge)
        if(index==4) Checkerboard(Modifier.fillMaxWidth().heightIn(min=180.dp,max=300.dp))
        Button(onClick={if(index==CaptureGuide.steps.lastIndex) onComplete() else page=index+1},modifier=Modifier.fillMaxWidth().testTag("a075-next")) { Text(stringResource(if(index==CaptureGuide.steps.lastIndex) R.string.a075_start else R.string.a075_next)) }
        if(index>0) OutlinedButton(onClick={page=index-1},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.a075_back)) }
        TextButton(onClick=onSkip,modifier=Modifier.fillMaxWidth().testTag("a075-skip")) { Text(stringResource(R.string.a075_skip)) }
    }
}
@Composable
fun CaptureHelp(modifier: Modifier = Modifier) { Text(stringResource(R.string.a075_overlap),modifier) }
@Composable
private fun Checkerboard(modifier: Modifier) {
    val description=stringResource(R.string.a075_board)
    Canvas(modifier.semantics { contentDescription=description }) {
        val unit=minOf(size.width/10f,size.height/7f)
        drawRect(Color.White)
        for(row in 0..CaptureGuide.INNER_ROWS) for(column in 0..CaptureGuide.INNER_COLUMNS) if(CaptureGuide.boardIsBlack(column,row))
            drawRect(Color.Black,Offset(column*unit,row*unit),Size(unit,unit))
    }
}
