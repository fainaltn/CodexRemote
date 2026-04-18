package dev.codexremote.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavHostTest {

    @Test
    fun `draft return target keeps the concrete parent route`() {
        val parentRoute = "sessions/server-1"

        val target = resolveDraftReturnTarget(parentRoute)

        assertEquals(parentRoute, target.route)
        assertFalse(target.inclusive)
    }

    @Test
    fun `draft return target falls back to popping the draft route`() {
        val target = resolveDraftReturnTarget(null)

        assertEquals(Screen.DraftSessionDetail.route, target.route)
        assertTrue(target.inclusive)
    }
}
