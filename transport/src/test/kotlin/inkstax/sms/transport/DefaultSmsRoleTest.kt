package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSmsRoleTest {

    @Test
    fun heldRoleNeedsNoScreenOnRoleSystemLevels() {
        assertEquals(
            DefaultSmsRoleStep.ALREADY_DEFAULT,
            defaultSmsRoleStep(apiLevel = 34, roleAvailable = true, roleHeld = true, isDefaultSmsPackage = false)
        )
    }

    @Test
    fun defaultPackageNeedsNoScreenEvenWithoutTheRole() {
        // A device without the role system can still report this
        // package as the default; that witness is enough.
        assertEquals(
            DefaultSmsRoleStep.ALREADY_DEFAULT,
            defaultSmsRoleStep(apiLevel = 34, roleAvailable = false, roleHeld = false, isDefaultSmsPackage = true)
        )
        assertEquals(
            DefaultSmsRoleStep.ALREADY_DEFAULT,
            defaultSmsRoleStep(apiLevel = 28, roleAvailable = false, roleHeld = false, isDefaultSmsPackage = true)
        )
    }

    @Test
    fun availableRoleIsRequestedDirectly() {
        assertEquals(
            DefaultSmsRoleStep.REQUEST_ROLE,
            defaultSmsRoleStep(apiLevel = 29, roleAvailable = true, roleHeld = false, isDefaultSmsPackage = false)
        )
        assertEquals(
            DefaultSmsRoleStep.REQUEST_ROLE,
            defaultSmsRoleStep(apiLevel = 34, roleAvailable = true, roleHeld = false, isDefaultSmsPackage = false)
        )
    }

    @Test
    fun unavailableRoleFallsBackToTheDefaultAppsScreen() {
        // The flow opens the screen itself; setup never dead-ends
        // and the user never hunts for the page.
        assertEquals(
            DefaultSmsRoleStep.OPEN_DEFAULT_APPS_SETTINGS,
            defaultSmsRoleStep(apiLevel = 29, roleAvailable = false, roleHeld = false, isDefaultSmsPackage = false)
        )
        assertEquals(
            DefaultSmsRoleStep.OPEN_DEFAULT_APPS_SETTINGS,
            defaultSmsRoleStep(apiLevel = 34, roleAvailable = false, roleHeld = false, isDefaultSmsPackage = false)
        )
    }

    @Test
    fun preRoleLevelsUseTheChangeDefaultScreen() {
        assertEquals(
            DefaultSmsRoleStep.CHANGE_DEFAULT,
            defaultSmsRoleStep(apiLevel = 26, roleAvailable = false, roleHeld = false, isDefaultSmsPackage = false)
        )
        assertEquals(
            DefaultSmsRoleStep.CHANGE_DEFAULT,
            defaultSmsRoleStep(apiLevel = 28, roleAvailable = false, roleHeld = false, isDefaultSmsPackage = false)
        )
    }

    @Test
    fun roleFactsAreIgnoredBeforeTheRoleSystem() {
        // Below API 29 there is no role system to answer, so a
        // stray held/available reading cannot promote the phone
        // past the change-default screen or to already-default.
        assertEquals(
            DefaultSmsRoleStep.CHANGE_DEFAULT,
            defaultSmsRoleStep(apiLevel = 28, roleAvailable = true, roleHeld = true, isDefaultSmsPackage = false)
        )
    }
}
