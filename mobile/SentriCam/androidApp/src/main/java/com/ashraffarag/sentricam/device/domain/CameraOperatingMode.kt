package com.ashraffarag.sentricam.device.domain

/**
 * Describes how this camera participates in the SentriCam product.
 *
 * This is intentionally independent from transient Hub connectivity states.
 * A paired camera remains [HubManaged] while the Hub is offline, reconnecting,
 * or its access token is being refreshed.
 */
sealed interface CameraOperatingMode {

    /**
     * The camera has no persisted Hub identity and can operate locally.
     */
    data object Standalone : CameraOperatingMode

    /**
     * The camera has a persisted identity issued by a SentriCam Hub.
     */
    data class HubManaged(
        val serverDeviceId: String,
    ) : CameraOperatingMode
}

/**
 * Resolves the camera operating mode from the persisted Hub identity.
 *
 * Registration screens and connection errors are not sources of truth for the
 * operating mode. The persisted server device identifier is.
 */
object CameraOperatingModeResolver {

    fun resolve(serverDeviceId: String?): CameraOperatingMode {
        val normalizedDeviceId = serverDeviceId
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        return if (normalizedDeviceId == null) {
            CameraOperatingMode.Standalone
        } else {
            CameraOperatingMode.HubManaged(
                serverDeviceId = normalizedDeviceId,
            )
        }
    }
}
