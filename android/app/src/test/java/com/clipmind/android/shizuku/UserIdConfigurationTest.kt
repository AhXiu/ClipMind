package com.clipmind.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UserIdConfigurationTest {
    @Test fun readGateIsClosedUntilClientConfiguresProfileUserId() {
        val configuration = UserIdConfiguration()
        assertNull(configuration.getOrNull())
        configuration.configure(10)
        assertEquals(10, configuration.getOrNull())
    }

    @Test fun invalidUserIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { UserIdConfiguration().configure(-1) }
    }
}
