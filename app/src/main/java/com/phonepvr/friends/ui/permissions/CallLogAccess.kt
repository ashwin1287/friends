package com.phonepvr.friends.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.phonepvr.friends.role.DialerRoleManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** What to do when something needs call-log access. See [CallLogAccessPolicy]. */
enum class CallLogAccessRoute {
    /** The permission is already held: go ahead. */
    GRANTED,

    /** Bondwidth is the phone app, so it may show the system permission prompt. */
    ASK_PERMISSION,

    /** Offer to make Bondwidth the phone app; Android grants call-log access with it. */
    ASK_TO_BECOME_PHONE_APP,

    /** Not the phone app and this device has no phone role to give. */
    UNAVAILABLE,
}

/**
 * Google Play's Call Log policy: only the default phone app may request
 * READ_CALL_LOG / WRITE_CALL_LOG, and it must hold the role *before* asking.
 * Bondwidth applies the same rule in every build, so the F-Droid and Play apps
 * behave alike. A permission granted earlier (for example by an older version)
 * is still used; it is only never *asked for* outside the role.
 *
 * Pure logic, unit-tested on the JVM. [CallLogRequestGuardTest] fails the build
 * if a screen asks for call-log access without going through here.
 */
object CallLogAccessPolicy {
    val PERMISSIONS: Set<String> = setOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
    )

    fun route(granted: Boolean, isPhoneApp: Boolean, canBecomePhoneApp: Boolean): CallLogAccessRoute =
        when {
            granted -> CallLogAccessRoute.GRANTED
            isPhoneApp -> CallLogAccessRoute.ASK_PERMISSION
            canBecomePhoneApp -> CallLogAccessRoute.ASK_TO_BECOME_PHONE_APP
            else -> CallLogAccessRoute.UNAVAILABLE
        }

    /** [permissions] minus the call-log ones unless Bondwidth is the phone app. */
    fun requestable(permissions: List<String>, isPhoneApp: Boolean): List<String> =
        if (isPhoneApp) permissions else permissions.filterNot { it in PERMISSIONS }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DialerRoleEntryPoint {
    fun dialerRoleManager(): DialerRoleManager
}

/** The app's [DialerRoleManager], for composables that have no view model of their own to ask. */
fun dialerRoleManager(context: Context): DialerRoleManager =
    EntryPointAccessors.fromApplication(context.applicationContext, DialerRoleEntryPoint::class.java)
        .dialerRoleManager()

/**
 * The one way a screen gets call-log access. Returns a function that takes the
 * action needing [permission] (READ_CALL_LOG or WRITE_CALL_LOG) and runs it once
 * access is there:
 * - already granted: runs it straight away;
 * - Bondwidth is the phone app: shows the system prompt, runs it on grant;
 * - otherwise: explains that call history needs Bondwidth as the phone app and
 *   offers the role (which grants call-log access too), running it after;
 * - no phone role on this device: says the feature isn't available here.
 *
 * Renders its own dialogs, so call it where those may appear.
 */
@Composable
fun rememberCallLogAccess(permission: String): (action: () -> Unit) -> Unit {
    require(permission in CallLogAccessPolicy.PERMISSIONS) { "Not a call-log permission: $permission" }
    val context = LocalContext.current
    val roles = remember(context) { dialerRoleManager(context) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showNeedsPhoneApp by remember { mutableStateOf(false) }
    var showUnavailable by remember { mutableStateOf(false) }
    var showRestricted by remember { mutableStateOf(false) }

    fun granted() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun runPendingIfGranted() {
        val action = pending
        pending = null
        if (action != null && granted()) action()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { runPendingIfGranted() }
    val requestPhoneApp = rememberPhoneAppRoleRequest(
        makeRequestIntent = roles::makeAcquireRoleIntent,
        isPhoneApp = roles::isDefaultDialer,
        onRefused = { showRestricted = true },
        onResult = { runPendingIfGranted() },
    )

    if (showNeedsPhoneApp) {
        CallHistoryNeedsPhoneAppDialog(
            onDismiss = {
                showNeedsPhoneApp = false
                pending = null
            },
            onMakePhoneApp = {
                showNeedsPhoneApp = false
                requestPhoneApp()
            },
        )
    }
    if (showUnavailable) {
        AlertDialog(
            onDismissRequest = { showUnavailable = false },
            title = { Text("Call history isn't available") },
            text = {
                Text(
                    "Android only lets the phone app use call history, and this " +
                        "device can't make Bondwidth its phone app.",
                )
            },
            confirmButton = { TextButton(onClick = { showUnavailable = false }) { Text("OK") } },
        )
    }
    if (showRestricted) {
        RestrictedDialerSettingsDialog(
            onDismiss = { showRestricted = false },
            onOpenAppInfo = { runCatching { context.startActivity(roles.makeAppInfoIntent()) } },
            onOpenDefaultApps = { runCatching { context.startActivity(roles.makeDefaultAppsSettingsIntent()) } },
        )
    }

    return { action ->
        val route = CallLogAccessPolicy.route(
            granted = granted(),
            isPhoneApp = roles.isDefaultDialer(),
            canBecomePhoneApp = roles.isRoleAvailable(),
        )
        when (route) {
            CallLogAccessRoute.GRANTED -> action()
            CallLogAccessRoute.ASK_PERMISSION -> {
                pending = action
                permissionLauncher.launch(permission)
            }
            CallLogAccessRoute.ASK_TO_BECOME_PHONE_APP -> {
                pending = action
                showNeedsPhoneApp = true
            }
            CallLogAccessRoute.UNAVAILABLE -> showUnavailable = true
        }
    }
}

/**
 * Shown when something needs call history while Bondwidth isn't the phone app.
 * Android (and Google Play) only let the phone app read or change the call log,
 * so the role is offered instead of a permission prompt.
 */
@Composable
fun CallHistoryNeedsPhoneAppDialog(
    onDismiss: () -> Unit,
    onMakePhoneApp: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call history needs Bondwidth as your phone app") },
        text = {
            Text(
                "Android only lets your phone app read and change call history. " +
                    "Make Bondwidth your phone app to use it here: your recents, " +
                    "calls counted toward your bonds, and call analytics. It all " +
                    "stays on this device.",
            )
        },
        confirmButton = {
            TextButton(onClick = onMakePhoneApp) { Text("Make it the phone app") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
