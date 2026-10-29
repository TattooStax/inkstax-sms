package org.fossify.messages.transport

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import inkstax.sms.transport.DefaultSmsRoleStep
import inkstax.sms.transport.defaultSmsRoleStep

/**
 * The one-tap default-SMS caller: the app side of the :transport
 * step definitions (see DefaultSmsRole.kt in :transport).
 *
 * A dedicated business phone reaches this from the startup flow
 * the moment its posture answer is in, so the system screen the
 * definitions name opens as part of setup itself — the user is
 * never sent to find a Settings page, scan a code, or type
 * anything, and their part is the one tap on the screen that
 * opens. Which screen that is follows the definitions exactly:
 * the role request where the role system offers the SMS role,
 * the change-default screen before the role system, and the
 * system's default-apps screen opened directly by this flow
 * where the role is unavailable — setup has no dead end.
 *
 * The role system exists only on Android 10 (API 29) and later,
 * so every read of it happens inside a level check the platform
 * tools can see, and the step definitions receive plain facts.
 *
 * The result returns under the caller's request code. A screen
 * opened this way does not report its outcome the same way on
 * every path — the default-apps screen in particular returns
 * without a grant result even when the user set the default
 * there — so a caller judges the return by reading
 * [isDefaultSmsApp] back, never by the result code alone.
 */
fun Activity.launchDefaultSmsRoleSetup(requestCode: Int, onAlreadyDefault: () -> Unit) {
    var roleAvailable = false
    var roleHeld = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = getSystemService(RoleManager::class.java)
        roleAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true
        roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
    }
    val step = defaultSmsRoleStep(
        apiLevel = Build.VERSION.SDK_INT,
        roleAvailable = roleAvailable,
        roleHeld = roleHeld,
        isDefaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(this) == packageName
    )
    when (step) {
        DefaultSmsRoleStep.ALREADY_DEFAULT -> onAlreadyDefault()
        DefaultSmsRoleStep.REQUEST_ROLE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = checkNotNull(getSystemService(RoleManager::class.java)) {
                    "The role step was chosen, so the role system answered"
                }
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS), requestCode)
            }
        }
        DefaultSmsRoleStep.CHANGE_DEFAULT -> {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivityForResult(intent, requestCode)
        }
        DefaultSmsRoleStep.OPEN_DEFAULT_APPS_SETTINGS -> {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), requestCode)
        }
    }
}

/**
 * Whether this app holds the default SMS role now, read from the
 * platform rather than from any screen's result code. The role
 * system's answer counts where it exists; the default-package
 * answer is the witness everywhere else and the fallback where
 * the role is unavailable.
 */
fun Activity.isDefaultSmsApp(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
            return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        }
    }
    return Telephony.Sms.getDefaultSmsPackage(this) == packageName
}
