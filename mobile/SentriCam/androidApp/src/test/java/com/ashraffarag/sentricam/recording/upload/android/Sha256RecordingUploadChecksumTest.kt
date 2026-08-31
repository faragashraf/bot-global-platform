package com.ashraffarag.sentricam.recording.upload.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256RecordingUploadChecksumTest {
    @Test
    fun checksumStreamsFileAsSha256Hex() {
        val file = File.createTempFile("sentricam-checksum", ".mp4")
        try {
            file.writeText("SentriCam")
            assertEquals(
                "8b8e25e410abe6dccd691b3ab8b6f93bd7cf2503d532d0914bbb9031cdc4a1f9",
                Sha256RecordingUploadChecksum().sha256(file.absolutePath),
            )
        } finally {
            file.delete()
        }
    }
}
