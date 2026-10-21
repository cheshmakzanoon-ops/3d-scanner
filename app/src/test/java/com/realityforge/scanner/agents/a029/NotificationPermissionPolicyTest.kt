package com.realityforge.scanner.agents.a029

import com.realityforge.scanner.processing.notifications.*
import org.junit.Assert.*
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun preApi33DoesNotInventRuntimePrompt() {
        val decision = evaluate(sdk = 32, granted = false, enabled = true)
        assertEquals(NotificationPermissionState.NOT_REQUIRED, decision.state)
        assertTrue(decision.canPostToNotificationDrawer)
        assertFalse(decision.shouldRequestRuntimePermission)
    }

    @Test
    fun firstApi33UseRequestsPermissionInVisibleFlow() {
        val decision = evaluate(
            sdk = 33,
            granted = false,
            enabled = false,
            requested = false,
            rationale = false,
        )
        assertEquals(NotificationPermissionState.REQUEST_PERMISSION, decision.state)
        assertTrue(decision.shouldRequestRuntimePermission)
        assertFalse(decision.canPostToNotificationDrawer)
    }

    @Test
    fun priorDenialWithRationaleIsDistinctFromSettingsRecovery() {
        val decision = evaluate(
            sdk = 36,
            granted = false,
            enabled = false,
            requested = true,
            rationale = true,
        )
        assertEquals(NotificationPermissionState.SHOW_RATIONALE, decision.state)
        assertTrue(decision.shouldShowRationale)
        assertFalse(decision.shouldOpenAppNotificationSettings)
    }

    @Test
    fun denialWithoutRationaleUsesSettingsRecovery() {
        val decision = evaluate(
            sdk = 36,
            granted = false,
            enabled = false,
            requested = true,
            rationale = false,
        )
        assertEquals(NotificationPermissionState.DENIED_WITHOUT_RATIONALE, decision.state)
        assertTrue(decision.shouldOpenAppNotificationSettings)
    }

    @Test
    fun grantedPermissionStillReportsSystemLevelDisablement() {
        val decision = evaluate(sdk = 36, granted = true, enabled = false)
        assertEquals(NotificationPermissionState.DISABLED_IN_SYSTEM_SETTINGS, decision.state)
        assertFalse(decision.canPostToNotificationDrawer)
        assertTrue(decision.shouldOpenAppNotificationSettings)
    }

    @Test
    fun disabledChannelIsDistinctFromWholeAppDisablement() {
        val decision = NotificationPermissionPolicy.evaluate(
            NotificationPermissionFacts(
                sdkInt = 36,
                runtimePermissionGranted = true,
                notificationsEnabled = true,
                channelEnabled = false,
                hasRequestedRuntimePermission = true,
                shouldShowRationale = false,
            ),
        )
        assertEquals(
            NotificationPermissionState.CHANNEL_DISABLED_IN_SYSTEM_SETTINGS,
            decision.state,
        )
        assertTrue(decision.shouldOpenAppNotificationSettings)
        assertFalse(decision.canPostToNotificationDrawer)
    }

    @Test
    fun foregroundServiceNotificationRequirementNeverDependsOnDrawerPermission() {
        val scenarios = listOf(
            evaluate(32, false, true),
            evaluate(36, false, false, requested = false),
            evaluate(36, false, false, requested = true),
            evaluate(36, true, true),
        )
        assertTrue(scenarios.all { it.foregroundServiceMustSupplyNotification })
    }

    private fun evaluate(
        sdk: Int,
        granted: Boolean,
        enabled: Boolean,
        requested: Boolean = true,
        rationale: Boolean = false,
    ) = NotificationPermissionPolicy.evaluate(
        NotificationPermissionFacts(
            sdkInt = sdk,
            runtimePermissionGranted = granted,
            notificationsEnabled = enabled,
            hasRequestedRuntimePermission = requested,
            shouldShowRationale = rationale,
        ),
    )
}
