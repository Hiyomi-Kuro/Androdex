package com.kaori.androdex.tunnel

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
}
