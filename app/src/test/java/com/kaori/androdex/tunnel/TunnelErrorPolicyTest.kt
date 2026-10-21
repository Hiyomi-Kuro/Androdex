package com.kaori.androdex.tunnel

import com.kaori.androdex.data.NetworkTimeoutDefaults
import com.kaori.androdex.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TunnelErrorPolicyTest {
    @Test fun classifiesTransientAndPermanentTunnelErrors() {
        assertTrue(TunnelErrorPolicy.retryableHttpStatus(429))
        assertTrue(TunnelErrorPolicy.retryableHttpStatus(503))
        assertFalse(TunnelErrorPolicy.retryableHttpStatus(401))
        assertFalse(TunnelErrorPolicy.retryableHttpStatus(403))
    }

    @Test fun treatsNetworkInterruptionAsRetryableButBadConfigurationAsPermanent() {
        assertTrue(TunnelErrorPolicy.retryableTransportFailure(IOException("network disconnected")))
        assertFalse(TunnelErrorPolicy.retryableTransportFailure(IllegalArgumentException("invalid tunnel id")))
    }

    @Test fun appliesOneHundredTwentySecondOrdinaryRequestBudgetAndKeepsPollingGrace() {
        assertEquals(120, UserSettings().networkTimeoutSeconds)
        assertEquals(120, TunnelTimeoutPolicy.ordinaryRequestSeconds(NetworkTimeoutDefaults.REQUEST_SECONDS))
        assertEquals(120, TunnelTimeoutPolicy.callTimeoutSeconds(120))
        assertEquals(145, TunnelTimeoutPolicy.pollRequestSeconds(120))
        assertEquals(20, NetworkTimeoutDefaults.CONNECT_SECONDS)
        assertEquals(120, TunnelTimeoutPolicy.ordinaryRequestSeconds(500))
        assertEquals(10, TunnelTimeoutPolicy.ordinaryRequestSeconds(1))
    }
}
