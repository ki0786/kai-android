package com.kai.assistant.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackendDiscoveryTest {
    private val phone = BackendDiscovery.LocalAddress("10.163.99.42", 24)

    @Test
    fun pcThatKeptItsLastNumberIsProbedFirst() {
        val hosts = BackendDiscovery.candidates(phone, savedHost = "10.120.148.19")
        assertEquals("10.163.99.19", hosts.first())
    }

    @Test
    fun withoutASavedHostNeighboursOfThePhoneComeFirst() {
        val hosts = BackendDiscovery.candidates(phone, savedHost = null)
        assertEquals(listOf("10.163.99.41", "10.163.99.43"), hosts.take(2).sorted())
    }

    @Test
    fun aSlash24ListsEveryOtherHostOnce() {
        val hosts = BackendDiscovery.candidates(phone, savedHost = "10.120.148.19")
        assertEquals(253, hosts.size)
        assertEquals(hosts.size, hosts.toSet().size)
        assertFalse("10.163.99.42" in hosts)
        assertFalse("10.163.99.0" in hosts)
        assertFalse("10.163.99.255" in hosts)
        assertTrue(hosts.all { it.startsWith("10.163.99.") })
    }

    @Test
    fun aWideSubnetIsCappedToThePhonesOwnSlash24() {
        val wide = BackendDiscovery.LocalAddress("10.163.99.42", 16)
        val hosts = BackendDiscovery.candidates(wide, savedHost = "10.120.148.19")
        assertTrue(hosts.size <= BackendDiscovery.MAX_HOSTS)
        assertTrue(hosts.all { it.startsWith("10.163.99.") })
        assertEquals("10.163.99.19", hosts.first())
    }

    @Test
    fun aSmallSubnetOnlyListsItsOwnHosts() {
        val small = BackendDiscovery.LocalAddress("192.168.1.10", 29)
        val hosts = BackendDiscovery.candidates(small, savedHost = null)
        assertEquals(
            setOf("192.168.1.9", "192.168.1.11", "192.168.1.12", "192.168.1.13", "192.168.1.14"),
            hosts.toSet(),
        )
        assertEquals(5, hosts.size)
    }

    @Test
    fun publicAndBrokenAddressesAreNeverScanned() {
        assertTrue(BackendDiscovery.candidates(BackendDiscovery.LocalAddress("8.8.8.8", 24), null).isEmpty())
        assertTrue(BackendDiscovery.candidates(BackendDiscovery.LocalAddress("172.32.0.5", 24), null).isEmpty())
        assertTrue(BackendDiscovery.candidates(BackendDiscovery.LocalAddress("not an ip", 24), null).isEmpty())
        assertTrue(BackendDiscovery.candidates(BackendDiscovery.LocalAddress("10.0.0.300", 24), null).isEmpty())
        assertEquals(253, BackendDiscovery.candidates(BackendDiscovery.LocalAddress("172.20.4.9", 24), null).size)
        assertEquals(253, BackendDiscovery.candidates(BackendDiscovery.LocalAddress("192.168.0.9", 24), null).size)
    }

    @Test
    fun onlyTheKaiHealthBodyCounts() {
        assertTrue(
            BackendDiscovery.isKaiHealth(
                """{"status":"ok","service":"KAI Backend","version":"0.1.0","providers_configured":["grok"]}""",
            ),
        )
        assertTrue(BackendDiscovery.isKaiHealth("""{ "service" : "kai-backend", "status" : "OK" }"""))
        assertFalse(BackendDiscovery.isKaiHealth("""{"status":"ok","service":"jupyter"}"""))
        assertFalse(BackendDiscovery.isKaiHealth("""{"status":"down","service":"KAI Backend"}"""))
        assertFalse(BackendDiscovery.isKaiHealth("<html>It works</html>"))
        assertFalse(BackendDiscovery.isKaiHealth(""))
    }

    @Test
    fun urlHelpersKeepThePort() {
        assertEquals("10.120.148.19", BackendDiscovery.hostOf("http://10.120.148.19:8000/"))
        assertEquals(8000, BackendDiscovery.portOf("http://10.120.148.19:8000"))
        assertEquals(8080, BackendDiscovery.portOf("http://10.120.148.19:8080/v1"))
        assertEquals(8000, BackendDiscovery.portOf("http://10.0.2.2"))
        assertEquals("http://10.163.99.19:8000", BackendDiscovery.urlFor("10.163.99.19"))
        assertNull(BackendDiscovery.hostOf("garbage"))
    }

    @Test
    fun firstRespondingHostWinsAndTheScanStopsEarly() = runBlocking {
        val hosts = BackendDiscovery.candidates(phone, savedHost = "10.120.148.19")
        val probed = AtomicInteger()
        val found = BackendDiscovery.firstResponding(hosts, probe = { host ->
            probed.incrementAndGet()
            delay(20)
            host == "10.163.99.19"
        }, concurrency = 4)
        assertEquals("10.163.99.19", found)
        assertTrue("probed ${probed.get()}", probed.get() < hosts.size)
    }

    @Test
    fun aHostFarDownTheListIsStillFound() = runBlocking {
        val hosts = BackendDiscovery.candidates(phone, savedHost = null)
        val found = BackendDiscovery.firstResponding(hosts, probe = { it == "10.163.99.250" })
        assertEquals("10.163.99.250", found)
    }

    @Test
    fun nothingAnsweringGivesNull() = runBlocking {
        val hosts = BackendDiscovery.candidates(phone, savedHost = null)
        assertNull(BackendDiscovery.firstResponding(hosts, probe = { false }))
        assertNull(BackendDiscovery.firstResponding(emptyList(), probe = { true }))
    }

    @Test
    fun probesThatThrowAreTreatedAsNoAnswer() = runBlocking {
        val found = BackendDiscovery.firstResponding(listOf("10.0.0.2", "10.0.0.3"), probe = { host ->
            if (host == "10.0.0.2") error("connection refused")
            true
        })
        assertEquals("10.0.0.3", found)
    }

    @Test
    fun theScanIsBoundedInTime() = runBlocking {
        val start = System.currentTimeMillis()
        val found = BackendDiscovery.firstResponding(
            listOf("10.0.0.2", "10.0.0.3"),
            probe = { delay(10_000); true },
            timeoutMs = 300,
        )
        assertNull(found)
        assertTrue(System.currentTimeMillis() - start < 3_000)
    }
}
