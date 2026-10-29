package inkstax.sms.transport

/**
 * One-tap default-SMS setup definitions, shared by every binding.
 *
 * Setup must never send the user hunting: no Settings page to find,
 * no code to type, no QR code to scan as a required step. When a
 * dedicated business phone answers the posture question, the setup
 * flow itself opens the exact system screen that grants the default
 * SMS role, and the user's part is the one tap on the screen that
 * opens. This file defines which screen that is, as a pure mapping
 * from the platform facts a caller reads, so every binding opens
 * the same screen for the same facts. There is no platform code
 * here — a caller supplies the facts and performs the step.
 *
 * On Android 10 (API 29) and later the role system is the way in:
 * where the SMS role is available, its request screen is the one
 * tap. Where the role is unavailable on such a device — an OEM
 * build without the role, or a device policy that removes it —
 * the request cannot be built at all, and dead-ending setup there
 * would strand a dedicated phone with no path forward. The
 * fallback is the system's default-apps screen, opened directly
 * by the flow rather than found by the user; it is still one
 * navigation the app performs, not a page the user must hunt for.
 * Before API 29 there is no role system, and the platform's
 * change-default-SMS screen is the one tap.
 *
 * A phone that already holds the role needs no screen at all:
 * setup proceeds. The held facts are read two ways — the role
 * system's answer and the platform's default-package answer —
 * because on a device without the role the second is the only
 * witness, and either one saying the role is held is enough.
 */

/** The one screen a dedicated phone's setup opens next, if any. */
enum class DefaultSmsRoleStep {
    /** The phone already holds the default SMS role; setup proceeds. */
    ALREADY_DEFAULT,

    /** Open the role system's request screen for the SMS role (API 29+). */
    REQUEST_ROLE,

    /** Open the platform's change-default-SMS screen (before API 29). */
    CHANGE_DEFAULT,

    /** Open the system's default-apps screen directly (role unavailable on API 29+). */
    OPEN_DEFAULT_APPS_SETTINGS
}

/** The first API level with the platform role system. */
const val DEFAULT_SMS_ROLE_MANAGER_MIN_API: Int = 29

/**
 * The setup step for the platform facts a caller read. [roleHeld]
 * and [roleAvailable] are the role system's answers and are only
 * meaningful at or above [DEFAULT_SMS_ROLE_MANAGER_MIN_API];
 * [isDefaultSmsPackage] is the default-package answer, available
 * on every level. Held outranks every screen: a phone that holds
 * the role is never asked for it again.
 */
fun defaultSmsRoleStep(
    apiLevel: Int,
    roleAvailable: Boolean,
    roleHeld: Boolean,
    isDefaultSmsPackage: Boolean
): DefaultSmsRoleStep {
    if (isDefaultSmsPackage || (apiLevel >= DEFAULT_SMS_ROLE_MANAGER_MIN_API && roleHeld)) {
        return DefaultSmsRoleStep.ALREADY_DEFAULT
    }
    if (apiLevel >= DEFAULT_SMS_ROLE_MANAGER_MIN_API) {
        return if (roleAvailable) {
            DefaultSmsRoleStep.REQUEST_ROLE
        } else {
            DefaultSmsRoleStep.OPEN_DEFAULT_APPS_SETTINGS
        }
    }
    return DefaultSmsRoleStep.CHANGE_DEFAULT
}
