package online.ptsmovies.stream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryTest {
    @Test fun candidatesSkipThePhoneItself() {
        val candidates = LanDiscovery.candidatesFor("192.168.29.75")
        assertEquals(253, candidates.size)
        assertTrue("192.168.29.1" in candidates)
        assertTrue("192.168.29.254" in candidates)
        assertFalse("192.168.29.75" in candidates)
        assertTrue(candidates.all { it.startsWith("192.168.29.") })
    }

    @Test fun invalidHostsProduceNoSweep() {
        assertTrue(LanDiscovery.candidatesFor("not-an-ip").isEmpty())
        assertTrue(LanDiscovery.candidatesFor("192.168.29").isEmpty())
        assertTrue(LanDiscovery.candidatesFor("192.168.29.999").isEmpty())
        assertTrue(LanDiscovery.candidatesFor("8.8.8.8").isEmpty())
        assertTrue(LanDiscovery.candidatesFor("192.168.29.4", 33).isEmpty())
    }

    @Test fun candidatesRespectSmallSubnetsAndBoundWideNetworks() {
        assertEquals(listOf("192.168.29.5"), LanDiscovery.candidatesFor("192.168.29.6", 30))
        assertEquals(listOf("192.168.29.6"), LanDiscovery.candidatesFor("192.168.29.7", 31))
        assertTrue(LanDiscovery.candidatesFor("192.168.29.7", 32).isEmpty())
        assertEquals(253, LanDiscovery.candidatesFor("10.20.30.5", 8).size)
    }

    @Test fun firstVerifiedResponderWins() = runBlocking {
        val probes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val found = LanDiscovery.discoverAmong(listOf("10.0.0.2", "10.0.0.3", "10.0.0.4")) { address ->
            probes += address
            address == "10.0.0.3"
        }
        assertEquals("10.0.0.3", found)
        assertTrue(probes.contains("10.0.0.3"))
    }

    @Test fun failedOrThrowingProbesReturnNull() = runBlocking {
        assertNull(LanDiscovery.discoverAmong(emptyList()) { true })
        assertNull(LanDiscovery.discoverAmong(listOf("10.0.0.2", "10.0.0.3")) { address ->
            if (address.endsWith(".2")) throw IllegalStateException("refused") else false
        })
    }

    @Test fun blockedMetadataSourceIsPerTitle() {
        assertTrue(metadataSourceBlocked("Peaky Blinders", "TVmaze"))
        assertTrue(metadataSourceBlocked("peaky  blinders", "TVmaze"))
        assertFalse(metadataSourceBlocked("Peaky Blinders", "Wikipedia"))
        assertFalse(metadataSourceBlocked("Breaking Bad", "TVmaze"))
    }
}
