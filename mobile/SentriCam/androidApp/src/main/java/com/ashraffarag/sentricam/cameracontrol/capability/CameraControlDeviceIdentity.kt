package com.ashraffarag.sentricam.cameracontrol.capability

/** Uses the Hub-authenticated identity for remotely addressed commands. */
fun cameraControlDeviceId(authenticatedDeviceId: String?, installationDeviceId: String): String =
    authenticatedDeviceId?.takeIf(String::isNotBlank) ?: installationDeviceId

/**
 * Keeps an already authenticated in-memory identity authoritative. Some older Android keystores
 * can transiently reject a second decrypt even though the active connection is healthy.
 */
fun cameraControlDeviceId(
    activeRegistrationDeviceId: String?,
    storedAuthenticatedDeviceId: () -> String?,
    installationDeviceId: String,
): String = cameraControlDeviceId(
    activeRegistrationDeviceId?.takeIf(String::isNotBlank)
        ?: runCatching(storedAuthenticatedDeviceId).getOrNull(),
    installationDeviceId,
)
