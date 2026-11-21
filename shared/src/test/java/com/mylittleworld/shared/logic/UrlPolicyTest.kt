package com.mylittleworld.shared.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun `https urls are accepted and normalised`() {
        assertEquals("https://learn.example.com/a?b=1", UrlPolicy.sanitiseApprovedUrl("https://learn.example.com/a?b=1"))
        assertEquals("https://learn.example.com", UrlPolicy.sanitiseApprovedUrl("learn.example.com"))
        assertEquals("https://learn.example.com", UrlPolicy.sanitiseApprovedUrl("  https://Learn.Example.com  "))
    }

    @Test
    fun `plain http is rejected unless local development host`() {
        assertNull(UrlPolicy.sanitiseApprovedUrl("http://learn.example.com"))
        assertEquals(
            "http://10.0.2.2:8787",
            UrlPolicy.sanitiseApprovedUrl("http://10.0.2.2:8787", allowCleartextLocalDev = true),
        )
        assertNull(UrlPolicy.sanitiseApprovedUrl("http://evil.example.com", allowCleartextLocalDev = true))
    }

    @Test
    fun `credentials fragments and shorteners are rejected`() {
        assertNull(UrlPolicy.sanitiseApprovedUrl("https://user:pass@learn.example.com"))
        assertNull(UrlPolicy.sanitiseApprovedUrl("https://learn.example.com/#x"))
        assertNull(UrlPolicy.sanitiseApprovedUrl("https://bit.ly/abc"))
        assertNull(UrlPolicy.sanitiseApprovedUrl(""))
        assertNull(UrlPolicy.sanitiseApprovedUrl("javascript:alert(1)"))
    }

    @Test
    fun `allow list permits approved host and subdomains only`() {
        val hosts = UrlPolicy.approvedHostsFor("https://learn.example.com")
        assertEquals(listOf("learn.example.com"), hosts)
        assertTrue(UrlPolicy.isAllowed("https://learn.example.com/lesson/3", hosts))
        assertTrue(UrlPolicy.isAllowed("https://cdn.learn.example.com/img.png", hosts))
        assertFalse(UrlPolicy.isAllowed("https://example.com", hosts))
        assertFalse(UrlPolicy.isAllowed("https://learn.example.com.evil.net", hosts))
        assertFalse(UrlPolicy.isAllowed("https://evil.net/?u=learn.example.com", hosts))
    }

    @Test
    fun `dangerous schemes are blocked even on approved host`() {
        val hosts = listOf("learn.example.com")
        assertFalse(UrlPolicy.isAllowed("javascript:alert(1)", hosts))
        assertFalse(UrlPolicy.isAllowed("file:///data/data/x", hosts))
        assertFalse(UrlPolicy.isAllowed("intent://scan#Intent;scheme=zxing;end", hosts))
        assertFalse(UrlPolicy.isAllowed("content://com.android.contacts/1", hosts))
        assertFalse(UrlPolicy.isAllowed("https://bit.ly/x", hosts))
        assertFalse(UrlPolicy.isAllowed("", hosts))
    }

    @Test
    fun `subdomain matching can be disabled`() {
        val hosts = listOf("learn.example.com")
        assertFalse(UrlPolicy.isAllowed("https://cdn.learn.example.com", hosts, allowSubdomains = false))
        assertTrue(UrlPolicy.isAllowed("https://learn.example.com", hosts, allowSubdomains = false))
    }

    @Test
    fun `empty allow list blocks everything`() {
        assertFalse(UrlPolicy.isAllowed("https://learn.example.com", emptyList()))
        assertTrue(UrlPolicy.approvedHostsFor(null).isEmpty())
        assertTrue(UrlPolicy.approvedHostsFor("").isEmpty())
    }
}
