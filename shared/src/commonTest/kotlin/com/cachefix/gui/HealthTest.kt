package com.cachefix.gui

import com.cachefix.gui.health.HealthKind
import com.cachefix.gui.health.parseCacheFixHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthTest {
    @Test
    fun okWithVersion() {
        val r = parseCacheFixHealth(
            200,
            """{"status":"ok","version":"4.3.0","forward_proxy":false}""",
        )
        assertEquals(HealthKind.OK, r.kind)
        assertEquals("4.3.0", r.version)
        assertEquals(false, r.forwardProxy)
    }

    @Test
    fun degradedWith503() {
        val r = parseCacheFixHealth(
            503,
            """{"status":"degraded","version":"4.3.1","failed_extensions":["x"],"hint":"check logs"}""",
        )
        assertEquals(HealthKind.DEGRADED, r.kind)
        assertEquals(503, r.httpStatus)
        assertEquals("check logs", r.hint)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("x"), r.failedExtensions as List<String>)
    }

    @Test
    fun okWithOnlyForwardProxy() {
        val r = parseCacheFixHealth(
            200,
            """{"status":"ok","forward_proxy":true}""",
        )
        assertEquals(HealthKind.OK, r.kind)
        assertEquals(true, r.forwardProxy)
        assertNull(r.version)
    }

    @Test
    fun foreignForUnknownJson() {
        assertEquals(
            HealthKind.FOREIGN,
            parseCacheFixHealth(200, """{"status":"ok"}""").kind,
        )
        assertEquals(
            HealthKind.FOREIGN,
            parseCacheFixHealth(200, """{"healthy":true}""").kind,
        )
        assertEquals(
            HealthKind.FOREIGN,
            parseCacheFixHealth(200, """[1,2,3]""").kind,
        )
        assertEquals(HealthKind.FOREIGN, parseCacheFixHealth(200, "not-json").kind)
    }

    @Test
    fun unreachableWhenNoStatusOrBody() {
        assertEquals(HealthKind.UNREACHABLE, parseCacheFixHealth(null, null).kind)
        assertEquals(HealthKind.UNREACHABLE, parseCacheFixHealth(null, "").kind)
    }
}
