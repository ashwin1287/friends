package com.phonepvr.friends.ui.permissions

import android.Manifest
import com.phonepvr.friends.ui.permissions.CallLogAccessRoute.ASK_PERMISSION
import com.phonepvr.friends.ui.permissions.CallLogAccessRoute.ASK_TO_BECOME_PHONE_APP
import com.phonepvr.friends.ui.permissions.CallLogAccessRoute.GRANTED
import com.phonepvr.friends.ui.permissions.CallLogAccessRoute.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Google Play only lets the default phone app ask for call-log access, so
 * Bondwidth never shows the call-log permission prompt unless it holds that role.
 */
class CallLogAccessPolicyTest {

    @Test
    fun alreadyGranted_runsStraightAway_whetherOrNotPhoneApp() {
        assertEquals(GRANTED, CallLogAccessPolicy.route(granted = true, isPhoneApp = false, canBecomePhoneApp = true))
        assertEquals(GRANTED, CallLogAccessPolicy.route(granted = true, isPhoneApp = true, canBecomePhoneApp = true))
    }

    @Test
    fun phoneApp_withoutTheGrant_mayAskForThePermission() {
        assertEquals(
            ASK_PERMISSION,
            CallLogAccessPolicy.route(granted = false, isPhoneApp = true, canBecomePhoneApp = true),
        )
    }

    @Test
    fun notPhoneApp_offersThePhoneAppRoleInsteadOfThePrompt() {
        assertEquals(
            ASK_TO_BECOME_PHONE_APP,
            CallLogAccessPolicy.route(granted = false, isPhoneApp = false, canBecomePhoneApp = true),
        )
    }

    @Test
    fun notPhoneApp_onADeviceWithoutThePhoneRole_isUnavailable() {
        assertEquals(
            UNAVAILABLE,
            CallLogAccessPolicy.route(granted = false, isPhoneApp = false, canBecomePhoneApp = false),
        )
    }

    @Test
    fun requestable_dropsCallLogPermissions_whenNotPhoneApp() {
        val asked = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.CALL_PHONE,
        )
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE),
            CallLogAccessPolicy.requestable(asked, isPhoneApp = false),
        )
    }

    @Test
    fun requestable_keepsEverything_whenPhoneApp() {
        val asked = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
        assertEquals(asked, CallLogAccessPolicy.requestable(asked, isPhoneApp = true))
    }
}
