package com.ashraffarag.sentricam.smartdetection.domain

data class RecognitionPrivacyPolicy(
    val localFirst: Boolean = true,
    val storesRawFaces: Boolean = false,
    val encryptsIdentityTemplates: Boolean = true,
    val requiresUploadConsent: Boolean = true,
    val deleteIdentityRemovesDerivedData: Boolean = true,
    val requiresActiveRecognitionIndicator: Boolean = true,
)

interface IdentityDerivedDataController {
    suspend fun deleteIdentityAndDerivedData(identityId: String): IdentityDeletionResult
}

sealed interface IdentityDeletionResult {
    data object Deleted : IdentityDeletionResult
    data object NotFound : IdentityDeletionResult
    data class Failed(val reasonCode: String) : IdentityDeletionResult
}

/** Policy contract only. V1 never collects or stores biometric material. */
object SentriCamRecognitionPrivacy {
    val policy = RecognitionPrivacyPolicy()
}
