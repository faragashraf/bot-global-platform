package com.ashraffarag.sentricam.recording.engine.android

import com.ashraffarag.sentricam.recording.engine.capability.RecordingMetadataStore
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.library.android.storage.RecordingSidecarMetadataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidRecordingMetadataStore(
    private val onFinalized: (RecordingSegmentMetadata) -> Unit = {},
) : RecordingMetadataStore {
    override suspend fun save(metadata: RecordingSegmentMetadata) = withContext(Dispatchers.IO) {
        RecordingSidecarMetadataStore.write(metadata)
        if (metadata.status == com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus.COMPLETED &&
            metadata.finalized && metadata.fileSizeBytes > 0
        ) {
            onFinalized(metadata)
        }
    }
}
