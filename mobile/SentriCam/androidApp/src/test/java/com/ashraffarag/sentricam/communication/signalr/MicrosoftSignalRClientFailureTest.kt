package com.ashraffarag.sentricam.communication.signalr

import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrosoftSignalRClientFailureTest {
    @Test
    fun connectedSocketAbortIsAClosedConnectionNotAProtocolFailure() {
        val failure = RuntimeException(
            "Software caused connection abort",
            SocketException("Software caused connection abort"),
        )

        assertEquals(
            SignalRFailureCode.CONNECTION_CLOSED,
            classifyInvocationFailure(failure, "CONNECTED"),
        )
    }

    @Test
    fun disconnectedHubStateIsRetryableEvenWithoutSocketSpecificMessage() {
        assertEquals(
            SignalRFailureCode.CONNECTION_CLOSED,
            classifyInvocationFailure(RuntimeException("Invocation failed"), "DISCONNECTED"),
        )
    }

    @Test
    fun genuineConnectedInvocationContractFailureRemainsNonRetryable() {
        assertEquals(
            SignalRFailureCode.CONTRACT_ERROR,
            classifyInvocationFailure(RuntimeException("Failed to deserialize invocation result"), "CONNECTED"),
        )
    }

    @Test
    fun ambiguousConnectedProtocolFailureIsRetryableWithACleanConnection() {
        assertEquals(
            SignalRFailureCode.PROTOCOL_ERROR,
            classifyInvocationFailure(RuntimeException("Invocation failed"), "CONNECTED"),
        )
        assertEquals(true, SignalRFailureCode.PROTOCOL_ERROR.retryAllowed)
    }
}
