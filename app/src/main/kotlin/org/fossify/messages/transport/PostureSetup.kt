package org.fossify.messages.transport

import android.app.Activity
import inkstax.sms.transport.rolePostureForDedicatedBusinessPhone
import inkstax.sms.transport.rolePostureFromStoredName
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.extensions.config

private const val POSTURE_ITEM_DEDICATED = 1
private const val POSTURE_ITEM_PERSONAL = 2

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
    if (rolePostureFromStoredName(config.rolePostureName).isAnswered) {
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
        config.rolePostureName = rolePostureForDedicatedBusinessPhone(isDedicated).name
        onPostureReady()
    }
}
