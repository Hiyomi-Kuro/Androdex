package com.kaori.androdex.tunnel

object TunnelErrorPolicy {
    fun retryableHttpStatus(status: Int): Boolean = status == 408 || status == 425 || status == 429 || status >= 500

    fun retryableTransportFailure(error: Throwable): Boolean = error !is IllegalArgumentException
}
