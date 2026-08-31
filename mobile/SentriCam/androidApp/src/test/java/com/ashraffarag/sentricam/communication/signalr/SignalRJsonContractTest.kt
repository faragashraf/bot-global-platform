package com.ashraffarag.sentricam.communication.signalr

import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandEnvelope
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalRJsonContractTest {
    private val gson = Gson()

    @Test
    fun cameraCommandIgnoresUnknownOptionalFields() {
        val value = gson.fromJson(
            """{
              "commandId":"3c90f42c-0f76-44aa-a2e2-eacbdbe0264e",
              "deviceId":"fa1a0ba8-59a4-4c80-97c1-ddc5771f5eed",
              "control":"torch",
              "value":{"boolean":true},
              "desiredSettings":{"lens":"back","zoom":1.0,"torch":true,"exposureCompensation":0,"preview":"visible","framesPerSecond":30,"resolution":"1280x720","bitrate":2500000,"quality":"medium","nightProfile":"auto"},
              "attempt":1,
              "requestedAtUtc":"2026-08-02T09:00:00Z",
              "optionalFutureField":{"version":2}
            }""".trimIndent(),
            CameraControlCommandEnvelope::class.java,
        )

        assertEquals("torch", value.control)
        assertEquals(true, value.value.boolean)
        assertEquals(30, value.desiredSettings.framesPerSecond)
    }

    @Test
    fun missingOptionalLiveCandidateFieldsRemainNull() {
        val value = gson.fromJson(
            """{
              "sessionId":"19d6cf91-55c7-4f0e-b7f3-809dfcb45d31",
              "candidate":"candidate-value"
            }""".trimIndent(),
            LiveIceCandidate::class.java,
        )

        assertNull(value.sdpMid)
        assertNull(value.sdpMLineIndex)
        assertNull(value.usernameFragment)
    }
}
