package org.fossify.messages.transport

import android.app.Activity
import android.content.Context
import inkstax.sms.transport.RolePosture
import inkstax.sms.transport.rolePostureForDedicatedBusinessPhone
import inkstax.sms.transport.rolePostureFromStoredAnswer
import java.io.File
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.extensions.config

private const val POSTURE_ITEM_DEDICATED = 1
private const val POSTURE_ITEM_PERSONAL = 2
private const val POSTURE_TOKEN_FILE = "role_posture_token"

/**
 * The posture this device actually runs under: the stored answer,
 * counted only with its on-device token. The token lives in
 * no-backup storage because app backup can restore the preference
 * onto another phone, and the setup question is per-device consent —
 * a restored answer must re-ask, never inherit (see
 * [rolePostureFromStoredAnswer] in :transport).
 */
fun Context.storedRolePosture(): RolePosture {
    val tokenName = runCatching {
        File(noBackupFilesDir, POSTURE_TOKEN_FILE).readText().trim()
    }.getOrNull()
    return rolePostureFromStoredAnswer(config.rolePostureName, tokenName)
}

/** Records the setup answer and its on-device token together. */
fun Context.storeRolePosture(posture: RolePosture) {
    config.rolePostureName = posture.name
    runCatching {
        File(noBackupFilesDir, POSTURE_TOKEN_FILE).writeText(posture.name)
    }
}

/**
 * The one posture setup question (SMS-3), asked until it is answered:
 * is this phone dedicated to the business line? The answer is stored
 * as the posture and read through the :transport seam types, so the
 * startup flow and later bindings all see the same value.
 *
 * The personal answer is pre-selected because it is the fail-safe
 * posture — it never leads to a default-role request, so accepting
 * the dialog as-is cannot take over a personal phone's messaging.
 * Cancelling leaves the posture unanswered; the question is asked
 * again on the next launch and the message flow does not start.
 */
fun Activity.ensureRolePosture(onPostureReady: () -> Unit) {
    if (storedRolePosture().isAnswered) {
        onPostureReady()
        return
    }

    val items = arrayListOf(
        RadioItem(POSTURE_ITEM_DEDICATED, getString(R.string.posture_dedicated)),
        RadioItem(POSTURE_ITEM_PERSONAL, getString(R.string.posture_personal))
    )
    RadioGroupDialog(
        activity = this,
        items = items,
        checkedItemId = POSTURE_ITEM_PERSONAL,
        titleId = R.string.posture_question
    ) { any ->
        val isDedicated = (any as Int) == POSTURE_ITEM_DEDICATED
        storeRolePosture(rolePostureForDedicatedBusinessPhone(isDedicated))
        onPostureReady()
    }
}
