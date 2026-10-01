package com.ashraffarag.sentricam.settings.android

import com.ashraffarag.sentricam.motion.capability.MotionSettingsRepository
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettingsRepository
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettingsRepository
import com.ashraffarag.sentricam.settings.domain.AppSettingsRepository
import com.ashraffarag.sentricam.settings.domain.AppSettingsSnapshot

/** Preserves the existing preference files while presenting one settings source to the UI. */
class CompositeAppSettingsRepository(
    private val cameraRepository: RecordingSettingsRepository,
    private val recordingRepository: RecordingEngineSettingsRepository,
    private val motionRepository: MotionSettingsRepository,
    private val monitoringRepository: MonitoringSettingsRepository,
) : AppSettingsRepository {
    override fun load() = AppSettingsSnapshot(
        camera = cameraRepository.load(),
        recording = recordingRepository.load(),
        motion = motionRepository.load(),
        monitoring = monitoringRepository.load(),
    )

    override fun save(settings: AppSettingsSnapshot) {
        cameraRepository.save(settings.camera)
        recordingRepository.save(settings.recording)
        motionRepository.save(settings.motion)
        monitoringRepository.save(settings.monitoring)
    }
}
