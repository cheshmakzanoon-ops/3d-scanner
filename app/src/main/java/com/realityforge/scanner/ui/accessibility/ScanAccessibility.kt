package com.realityforge.scanner.ui.accessibility

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Labels are resolved by the owning feature resources; helpers never hard-code English. */
fun Modifier.scanHeading() = semantics { heading() }
fun Modifier.scanStatus(description: String, announceChange: Boolean = false) = semantics {
    stateDescription = description
    if (announceChange) liveRegion = LiveRegionMode.Polite
}
fun Modifier.scanProgress(stage: String, completed: Long?, total: Long?) = semantics {
    contentDescription = stage
    progressBarRangeInfo = if (completed != null && total != null && total > 0 && completed in 0..total)
        ProgressBarRangeInfo((completed.toDouble() / total).toFloat(), 0f..1f)
    else ProgressBarRangeInfo.Indeterminate
}
fun Modifier.scanActions(actions: List<Pair<String, () -> Boolean>>) = semantics {
    require(actions.map { it.first }.distinct().size == actions.size)
    require(actions.all { it.first.isNotBlank() })
    customActions = actions.map { CustomAccessibilityAction(it.first, it.second) }
}
fun Modifier.scanToggle(label: String, description: String) = semantics(mergeDescendants = true) {
    contentDescription = label
    stateDescription = description
    role = Role.Switch
}
fun Modifier.scanTouchTarget() = sizeIn(minWidth = 48.dp, minHeight = 48.dp)

/** Announce stage/terminal transitions once, not every numerical progress tick. */
class ScanAnnouncementPolicy {
    private var last: Pair<String, String>? = null
    fun shouldAnnounce(jobIdentity: String, stageCode: String, terminal: Boolean = false): Boolean {
        require(jobIdentity.isNotBlank() && stageCode.isNotBlank())
        val key = jobIdentity to if (terminal) "terminal:$stageCode" else stageCode
        if (last == key) return false
        last = key
        return true
    }
    fun reset() { last = null }
}

object AccessibilityAuditContract {
    val requiredScreens = setOf("library", "capture", "review", "processing", "studio", "export", "preferences", "onboarding")
    val requiredChecks = setOf("talkback", "keyboard", "switch_access", "large_text_200", "rtl", "touch_targets", "focus_restore", "gesture_alternatives", "destructive_scope")
    enum class Result { PASS, FAIL, NOT_RUN, BLOCKED_EXTERNAL }
    data class Evidence(val screen: String, val check: String, val result: Result, val artifactSha256: String, val device: String, val evidence: String)
    fun incomplete(records: List<Evidence>, artifactSha256: String): Set<Pair<String, String>> {
        require(artifactSha256.matches(Regex("[a-f0-9]{64}")))
        require(records.all { it.screen in requiredScreens && it.check in requiredChecks && it.evidence.isNotBlank() && it.device.isNotBlank() })
        val groups = records.groupBy { it.screen to it.check }
        return requiredScreens.flatMap { screen -> requiredChecks.map { screen to it } }.filter { key ->
            val rows = groups[key].orEmpty()
            rows.size != 1 || rows.single().artifactSha256 != artifactSha256 || rows.single().result != Result.PASS
        }.toSet()
    }
}
