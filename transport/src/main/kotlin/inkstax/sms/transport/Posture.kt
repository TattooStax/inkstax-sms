package inkstax.sms.transport

/**
 * Role posture, bound at setup by device class through ONE question:
 * "Is this phone dedicated to the business line?"
 *
 * The answer decides what the app may do on this device, and it is a
 * property of the device class, not a mode flag scattered through UI
 * code: every consumer reads this one value. A personal phone stays
 * permissions-only — the owner's own messenger keeps the default SMS
 * role (and RCS with it), so this app must never request the default
 * role there; because replies then go out from a phone that may carry
 * more than one SIM, the business subscription must be pinned and a
 * send must never fall back to an unpinned SIM (enforced where sends
 * are placed; the policy is stated here so bindings cannot drift).
 * A dedicated business phone may hold the default SMS role, which is
 * the posture the bridge expects for a business line.
 *
 * [UNSET] is the state before the question is answered. It is the
 * fail-safe value: nothing may request the default role while the
 * posture is unanswered or the stored value is unrecognised, because
 * requesting the role is the one step that cannot be quietly undone
 * for a personal phone's owner.
 */
enum class RolePosture {
    UNSET,
    PERMISSIONS_ONLY,
    DEFAULT_ROLE;

    /** Only a dedicated business phone may be asked for the default SMS role. */
    val mayRequestDefaultRole: Boolean
        get() = this == DEFAULT_ROLE

    /** Personal phones pin the business subscription; sends never use an unpinned SIM. */
    val requiresPinnedSubscription: Boolean
        get() = this == PERMISSIONS_ONLY

    val isAnswered: Boolean
        get() = this != UNSET
}

/** The one setup question, as a pure mapping so every binding answers it the same way. */
fun rolePostureForDedicatedBusinessPhone(isDedicatedBusinessPhone: Boolean): RolePosture {
    return if (isDedicatedBusinessPhone) {
        RolePosture.DEFAULT_ROLE
    } else {
        RolePosture.PERMISSIONS_ONLY
    }
}

/**
 * Reads a stored posture name back. An absent or unrecognised value
 * maps to [RolePosture.UNSET] rather than guessing a posture, so a
 * corrupt or future value can never silently grant the default role.
 */
fun rolePostureFromStoredName(storedName: String?): RolePosture {
    return RolePosture.entries.firstOrNull { it.name == storedName } ?: RolePosture.UNSET
}

/**
 * Reads the posture answer as setup consent given on THIS device.
 * The answer itself lives in app preferences, which Android backup
 * can restore onto a different phone; the setup question is
 * per-device consent, so a stored answer only counts when the
 * on-device token (kept in storage backup never carries) names the
 * same posture. A restored answer without its token reads back as
 * [RolePosture.UNSET] and the question is asked again on that
 * device, instead of a dedicated phone's answer silently turning a
 * personal phone toward the default role.
 */
fun rolePostureFromStoredAnswer(storedName: String?, onDeviceTokenName: String?): RolePosture {
    if (storedName == null || storedName != onDeviceTokenName) {
        return RolePosture.UNSET
    }
    return rolePostureFromStoredName(storedName)
}
