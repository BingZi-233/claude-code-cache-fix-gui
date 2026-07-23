package com.cachefix.gui

import com.cachefix.gui.proxy.COMPATIBLE_RANGE
import com.cachefix.gui.proxy.ProxyCandidate
import com.cachefix.gui.proxy.rankProxyCandidates
import com.cachefix.gui.proxy.selectProxy
import com.cachefix.gui.proxy.satisfiesCompatible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyDiscoverTest {
    @Test
    fun compatibleRangeLocked() {
        assertEquals(">=4.3.0 <5", COMPATIBLE_RANGE)
    }

    @Test
    fun satisfiesCompatibleAccepts43AndLater4x() {
        assertTrue(satisfiesCompatible("4.3.0"))
        assertTrue(satisfiesCompatible("4.3.1"))
        assertTrue(satisfiesCompatible("4.99.0"))
        assertTrue(satisfiesCompatible("v4.3.0"))
    }

    @Test
    fun satisfiesCompatibleRejectsOutOfRange() {
        assertFalse(satisfiesCompatible("4.2.9"))
        assertFalse(satisfiesCompatible("4.0.0"))
        assertFalse(satisfiesCompatible("3.9.0"))
        assertFalse(satisfiesCompatible("5.0.0"))
        assertFalse(satisfiesCompatible(""))
        assertFalse(satisfiesCompatible(null))
    }

    @Test
    fun rankOrdersExplicitPathNpmSidecar() {
        val ranked = rankProxyCandidates(
            listOf(
                ProxyCandidate("sidecar", "/app/sidecar", "4.3.0"),
                ProxyCandidate("npm-global", "/npm/bin", "4.3.0"),
                ProxyCandidate("path", "/usr/bin/cache-fix-proxy", "4.3.0"),
                ProxyCandidate("explicit", "/opt/proxy", "4.3.0"),
            ),
        )
        assertEquals(
            listOf("explicit", "path", "npm-global", "sidecar"),
            ranked.map { it.source },
        )
    }

    @Test
    fun selectPrefersPathOverSidecar() {
        val selected = selectProxy(
            listOf(
                ProxyCandidate("sidecar", "/app/sidecar", "4.3.0"),
                ProxyCandidate("path", "/usr/bin/cache-fix-proxy", "4.4.0"),
            ),
        )
        assertEquals("path", selected!!.source)
        assertEquals("/usr/bin/cache-fix-proxy", selected.path)
    }

    @Test
    fun incompatiblePathPrefersSidecar() {
        val selected = selectProxy(
            listOf(
                ProxyCandidate("path", "/usr/bin/cache-fix-proxy", "4.2.0"),
                ProxyCandidate("sidecar", "/app/sidecar", "4.3.0"),
            ),
        )
        assertEquals("sidecar", selected!!.source)
    }

    @Test
    fun respectsCompatibleFalseFlag() {
        val selected = selectProxy(
            listOf(
                ProxyCandidate(
                    source = "path",
                    path = "/usr/bin/cache-fix-proxy",
                    version = "4.9.0",
                    compatible = false,
                ),
                ProxyCandidate("sidecar", "/app/sidecar", "4.3.0"),
            ),
        )
        assertEquals("sidecar", selected!!.source)
    }

    @Test
    fun returnsNullWhenNothingCompatible() {
        val selected = selectProxy(
            listOf(
                ProxyCandidate("path", "/old", "3.0.0"),
                ProxyCandidate("npm-global", "/npm", "4.1.0"),
            ),
        )
        assertNull(selected)
    }
}
