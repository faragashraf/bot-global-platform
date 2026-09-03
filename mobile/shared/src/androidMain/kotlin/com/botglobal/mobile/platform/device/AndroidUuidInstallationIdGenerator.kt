package com.botglobal.mobile.platform.device

import java.util.UUID

object AndroidUuidInstallationIdGenerator : InstallationIdGenerator {
    override fun generate(): InstallationId = InstallationId(UUID.randomUUID().toString())
}
