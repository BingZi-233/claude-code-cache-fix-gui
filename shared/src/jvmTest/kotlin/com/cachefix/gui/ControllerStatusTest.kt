package com.cachefix.gui

import com.cachefix.gui.controller.Controller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the real shipped Controller.getStatus() entry (JVM).
 * Proves status payload has coherent phase/port/mode fields.
 */
class ControllerStatusTest {
    @Test
    fun getStatusReturnsCoherentPhasePortMode() {
        val status = Controller.getStatus()
        val phase = status["phase"] as String
        assertTrue(
            phase in setOf(
                "Discovering", "Stopped", "Starting", "Running",
                "Degraded", "Error", "Attached",
            ),
            "unexpected phase: $phase",
        )
        val port = status["port"] as Int
        assertTrue(port in 1..65535, "port out of range: $port")
        val mode = status["mode"] as String
        assertTrue(mode == "reverse" || mode == "forward", "mode=$mode")
        assertEquals("kmp-jvm", status["engine"])
        @Suppress("UNCHECKED_CAST")
        val health = status["health"] as Map<String, Any?>
        assertNotNull(health["kind"])
        assertTrue(status.containsKey("compatibleRange"))
    }

    @Test
    fun statusJsonIsParseableObjectWithRequiredKeys() {
        val raw = Controller.statusJson()
        assertTrue(raw.startsWith("{"))
        assertTrue(raw.contains("\"phase\""))
        assertTrue(raw.contains("\"port\""))
        assertTrue(raw.contains("\"mode\""))
        assertTrue(raw.contains("\"engine\""))
    }
}
