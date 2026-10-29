package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureTest {

    @Test
    fun `dedicated business phone maps to default role posture`() {
        assertEquals(RolePosture.DEFAULT_ROLE, rolePostureForDedicatedBusinessPhone(true))
    }

    @Test
    fun `personal phone maps to permissions only posture`() {
        assertEquals(RolePosture.PERMISSIONS_ONLY, rolePostureForDedicatedBusinessPhone(false))
    }

    @Test
    fun `only the default role posture may request the default sms role`() {
        assertTrue(RolePosture.DEFAULT_ROLE.mayRequestDefaultRole)
        assertFalse(RolePosture.PERMISSIONS_ONLY.mayRequestDefaultRole)
        assertFalse(RolePosture.UNSET.mayRequestDefaultRole)
    }

    @Test
    fun `only the personal posture requires a pinned subscription`() {
        assertTrue(RolePosture.PERMISSIONS_ONLY.requiresPinnedSubscription)
        assertFalse(RolePosture.DEFAULT_ROLE.requiresPinnedSubscription)
        assertFalse(RolePosture.UNSET.requiresPinnedSubscription)
    }

    @Test
    fun `stored names round trip and unknown values fail safe to unset`() {
        for (posture in RolePosture.entries) {
            assertEquals(posture, rolePostureFromStoredName(posture.name))
        }
        assertEquals(RolePosture.UNSET, rolePostureFromStoredName(null))
        assertEquals(RolePosture.UNSET, rolePostureFromStoredName(""))
        assertEquals(RolePosture.UNSET, rolePostureFromStoredName("default_role"))
        assertEquals(RolePosture.UNSET, rolePostureFromStoredName("SOMETHING_ELSE"))
    }

    @Test
    fun `unset is the only unanswered posture`() {
        assertFalse(RolePosture.UNSET.isAnswered)
        assertTrue(RolePosture.PERMISSIONS_ONLY.isAnswered)
        assertTrue(RolePosture.DEFAULT_ROLE.isAnswered)
    }
}
