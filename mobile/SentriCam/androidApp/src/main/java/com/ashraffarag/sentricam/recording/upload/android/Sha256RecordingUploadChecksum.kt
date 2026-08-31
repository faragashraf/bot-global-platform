package com.ashraffarag.sentricam.recording.upload.android

import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadChecksum
import java.io.File
import java.security.MessageDigest

class Sha256RecordingUploadChecksum : RecordingUploadChecksum {
    override fun sha256(absolutePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(absolutePath).inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
