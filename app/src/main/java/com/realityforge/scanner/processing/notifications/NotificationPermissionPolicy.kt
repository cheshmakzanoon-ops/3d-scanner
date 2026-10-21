package com.realityforge.scanner.processing.notifications

/** Platform facts supplied by the Activity/UI owner at the moment permission state is rendered. */
data class NotificationPermissionFacts(
    val sdkInt: Int,
    val runtimePermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val hasRequestedRuntimePermission: Boolean,
    val shouldShowRationale: Boolean,
    val channelEnabled: Boolean = true,
) {
    init {
        require(sdkInt > 0) { "sdkInt must be positive" }
    }
}

enum class NotificationPermissionState {
    /** API 32 and lower: no POST_NOTIFICATIONS runtime prompt is required. */
    NOT_REQUIRED,

    /** API 33+ runtime permission is granted and app notifications are enabled. */
    GRANTED,

    /** The app has not asked yet; request only in a user-visible explanatory flow. */
    REQUEST_PERMISSION,

    /** Android reports that rationale should be shown before another request. */
    SHOW_RATIONALE,

    /** Permission is denied and Android does not request a rationale; direct users to settings. */
    DENIED_WITHOUT_RATIONALE,

    /** Runtime permission may be granted, but notifications are disabled at app/system level. */
    DISABLED_IN_SYSTEM_SETTINGS,

    /** App notifications are allowed, but the relevant processing/result channel is disabled. */
    CHANNEL_DISABLED_IN_SYSTEM_SETTINGS,
}

data class NotificationPermissionDecision(
    val state: NotificationPermissionState,
    val canPostToNotificationDrawer: Boolean,
    val shouldRequestRuntimePermission: Boolean,
    val shouldShowRationale: Boolean,
    val shouldOpenAppNotificationSettings: Boolean,
    /** Android still requires an FGS notification object even when drawer permission is denied. */
    val foregroundServiceMustSupplyNotification: Boolean = true,
)

object NotificationPermissionPolicy {
    const val POST_NOTIFICATIONS_API = 33

    fun evaluate(facts: NotificationPermissionFacts): NotificationPermissionDecision {
        if (facts.sdkInt < POST_NOTIFICATIONS_API) {
            return when {
                !facts.notificationsEnabled ->
                    decision(NotificationPermissionState.DISABLED_IN_SYSTEM_SETTINGS)
                !facts.channelEnabled ->
                    decision(NotificationPermissionState.CHANNEL_DISABLED_IN_SYSTEM_SETTINGS)
                else -> decision(NotificationPermissionState.NOT_REQUIRED)
            }
        }

        if (!facts.runtimePermissionGranted) {
            return when {
                facts.shouldShowRationale -> decision(NotificationPermissionState.SHOW_RATIONALE)
                !facts.hasRequestedRuntimePermission ->
                    decision(NotificationPermissionState.REQUEST_PERMISSION)
                else -> decision(NotificationPermissionState.DENIED_WITHOUT_RATIONALE)
            }
        }

        return when {
            !facts.notificationsEnabled ->
                decision(NotificationPermissionState.DISABLED_IN_SYSTEM_SETTINGS)
            !facts.channelEnabled ->
                decision(NotificationPermissionState.CHANNEL_DISABLED_IN_SYSTEM_SETTINGS)
            else -> decision(NotificationPermissionState.GRANTED)
        }
    }

    private fun decision(state: NotificationPermissionState): NotificationPermissionDecision =
        NotificationPermissionDecision(
            state = state,
            canPostToNotificationDrawer = state == NotificationPermissionState.NOT_REQUIRED ||
                state == NotificationPermissionState.GRANTED,
            shouldRequestRuntimePermission = state == NotificationPermissionState.REQUEST_PERMISSION,
            shouldShowRationale = state == NotificationPermissionState.SHOW_RATIONALE,
            shouldOpenAppNotificationSettings =
                state == NotificationPermissionState.DENIED_WITHOUT_RATIONALE ||
                    state == NotificationPermissionState.DISABLED_IN_SYSTEM_SETTINGS ||
                    state == NotificationPermissionState.CHANNEL_DISABLED_IN_SYSTEM_SETTINGS,
        )
}
