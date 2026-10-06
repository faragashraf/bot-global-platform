package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.update.AppVersionPolicy

interface NqrbUpdatePolicyGateway {
    suspend fun versionPolicy(currentVersion: String, platform: String): AppVersionPolicy
}

object AllowCurrentNqrbUpdatePolicyGateway : NqrbUpdatePolicyGateway {
    override suspend fun versionPolicy(currentVersion: String, platform: String) =
        AppVersionPolicy(currentVersion, currentVersion, currentVersion)
}
