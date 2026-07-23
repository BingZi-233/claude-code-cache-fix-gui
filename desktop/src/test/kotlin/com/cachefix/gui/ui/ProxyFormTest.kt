package com.cachefix.gui.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives shipped ProxyForm.toProxyEnv / mergedFromProxyEnv. */
class ProxyFormTest {
    @Test
    fun toProxyEnvSetsUpstreamAndDebug() {
        val env = ProxyForm(
            upstream = "https://api.example.com",
            debug = true,
            downloadRewrite = true,
        ).toProxyEnv()
        assertEquals("https://api.example.com", env["CACHE_FIX_PROXY_UPSTREAM"])
        assertEquals("1", env["CACHE_FIX_DEBUG"])
        assertEquals("on", env["CACHE_FIX_DOWNLOAD_REWRITE"])
    }

    @Test
    fun dualCaseProxyKeys() {
        val env = ProxyForm(httpsProxy = "http://corp:8080", noProxy = "localhost").toProxyEnv()
        assertEquals("http://corp:8080", env["HTTPS_PROXY"])
        assertEquals("http://corp:8080", env["https_proxy"])
        assertEquals("localhost", env["NO_PROXY"])
        assertEquals("localhost", env["no_proxy"])
    }

    @Test
    fun extraEnvSkipsDedicatedKeys() {
        val env = ProxyForm(
            extraEnvText = "CACHE_FIX_DEBUG=1\nCUSTOM_KEY=hello\n# comment\nbadline",
        ).toProxyEnv()
        assertFalse("CACHE_FIX_DEBUG" in env) // dedicated; only via switch
        assertEquals("hello", env["CUSTOM_KEY"])
    }

    @Test
    fun rejectUnauthorizedOffWritesZero() {
        val env = ProxyForm(rejectUnauthorized = false).toProxyEnv()
        assertEquals("0", env["CACHE_FIX_PROXY_REJECT_UNAUTHORIZED"])
    }

    @Test
    fun mergedFromProxyEnvRoundTrip() {
        val pe = mapOf(
            "CACHE_FIX_PROXY_UPSTREAM" to "https://x",
            "CACHE_FIX_DEBUG" to "1",
            "HTTPS_PROXY" to "http://p:1",
            "CUSTOM" to "v",
        )
        val form = ProxyForm().mergedFromProxyEnv(pe)
        assertEquals("https://x", form.upstream)
        assertTrue(form.debug)
        assertEquals("http://p:1", form.httpsProxy)
        assertTrue(form.extraEnvText.contains("CUSTOM=v"))
    }
}
