package com.botglobal.mobile.platform.chat

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

class ChatVoiceDurationTests {
    @Test fun finalizedFixtureUsesSampleTimeAndCeilingInsteadOfRecorderTimer() {
        // Same tracked backend AAC: 87 complete frames + 136 samples at 44100 Hz.
        // Encoder priming makes this 2024 ms even though the generated input was 2 seconds.
        val recorderElapsedEstimate = 2179
        assertEquals(1525, ChatAacFixture.bytes.size)
        assertEquals(2024, ChatVoiceDuration.milliseconds(ChatAacFixture.bytes))
        assertNotEquals(recorderElapsedEstimate, ChatVoiceDuration.milliseconds(ChatAacFixture.bytes))
        assertEquals((89224L * 1000 + 44100 - 1) / 44100, 2024L)
    }

    @Test fun mediaRecorderTimescaleMayDifferFromAacSampleRate() {
        val fixture = ChatAacFixture.bytes
        fun changed(vararg edits: Triple<String, Int, Long>): ByteArray {
            val bytes = fixture.copyOf()
            edits.forEach { (type, offset, value) ->
                val at = bytes.indices.first { p -> p + 4 <= bytes.size && bytes.copyOfRange(p, p + 4).decodeToString() == type } + 4 + offset
                repeat(4) { bytes[at + it] = (value shr (24 - 8 * it)).toByte() }
            }
            return bytes
        }
        val samsungStyleTimescale = changed(
            Triple("mdhd", 12, 1000),
            Triple("mdhd", 16, 2024),
            Triple("stts", 12, 23),
            Triple("stts", 20, 23),
        )
        assertEquals(2024, ChatVoiceDuration.milliseconds(samsungStyleTimescale))
    }

    @Test fun malformedTruncatedExternalAndOverboundContainersFailClosed() {
        val fixture = ChatAacFixture.bytes
        fun changed(type: String, offset: Int, value: Long): ByteArray {
            val bytes = fixture.copyOf()
            val at = bytes.indices.first { p -> p + 4 <= bytes.size && bytes.copyOfRange(p, p + 4).decodeToString() == type } + 4 + offset
            repeat(4) { bytes[at + it] = (value shr (24 - 8 * it)).toByte() }
            return bytes
        }
        val invalid = listOf(byteArrayOf(1, 2, 3), fixture.copyOf(100), ByteArray(ChatVoiceDuration.MaxBytes + 1),
            changed("mdhd", 12, 0), changed("mdhd", 16, 44100L * 301),
            changed("stts", 8, 100_001), changed("stts", 12, 900),
            changed("stco", 8, 0), changed("stsz", 8, 1), changed("url ", 0, 0),
            changed("stts", 8, 13_000), changed("stts", 20, 4096))
        invalid.forEach { assertNull(ChatVoiceDuration.milliseconds(it)) }
    }
}

/** Exact existing backend Fixtures/aac-silence.m4a, embedded to keep common tests platform-neutral.
 * SHA-256 ce66f2e7c1e9c8d940ed0b56b476f1900ba6f0cfacdb71b0cd1b43f5ddc26411.
 * No new media asset; backend oracle asserts both digest and sample-derived duration. */
internal object ChatAacFixture {
    const val hash = "ce66f2e7c1e9c8d940ed0b56b476f1900ba6f0cfacdb71b0cd1b43f5ddc26411"
    const val length = 1525L
    const val duration = 2024
    @OptIn(ExperimentalEncodingApi::class)
    val bytes: ByteArray get() = Base64.decode(
        "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAAhmcmVlAAABd21kYXTcAExhdmM2My4xLjEwMQACMEAOARggBwEYIAcBGCAH" +
        "ARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARgg" +
        "BwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEY" +
        "IAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcB" +
        "GCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAH" +
        "ARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHAAAEWm1vb3YAAABsbXZoZAAAAAAAAAAAAAAAAAAArEQAAViIAAEA" +
        "AAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAIAAAOFdHJhawAAAFx0a2hkAAAAAwAAAAAAAAAAAAAAAQAAAAAAAViIAAAAAAAAAAAAAAABAQAAAAABAAAAAAAAAAAAAAAAAAAA" +
        "AQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAFYiAAABAAAAQAAAAAC/W1kaWEAAAAg" +
        "bWRoZAAAAAAAAAAAAAAAAAAArEQAAVyIVcQAAAAAAC1oZGxyAAAAAAAAAABzb3VuAAAAAAAAAAAAAAAAU291bmRIYW5kbGVyAAAA" +
        "AqhtaW5mAAAAEHNtaGQAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAmxzdGJsAAAAanN0c2QA" +
        "AAAAAAAAAQAAAFptcDRhAAAAAAAAAAEAAAAAAAAAAAABABAAAAAArEQAAAAAADZlc2RzAAAAAAOAgIAlAAEABICAgBdAFQAAAAAA" +
        "+gAAAAWrBYCAgAUSCFblAAaAgIABAgAAACBzdHRzAAAAAAAAAAIAAABXAAAEAAAAAAEAAACIAAAAHHN0c2MAAAAAAAAAAQAAAAEA" +
        "AABYAAAAAQAAAXRzdHN6AAAAAAAAAAAAAABYAAAAEwAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAE" +
        "AAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAA" +
        "BAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAA" +
        "AAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQA" +
        "AAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAE" +
        "AAAABAAAABRzdGNvAAAAAAAAAAEAAAAsAAAAGnNncGQBAAAAcm9sbAAAAAIAAAAB//8AAAAcc2JncAAAAAByb2xsAAAAAQAAAFgA" +
        "AAABAAAAYXVkdGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAsaWxzdAAAACSpdG9vAAAA" +
        "HGRhdGEAAAABAAAAAExhdmY2My4xLjEwMQ==")
}
