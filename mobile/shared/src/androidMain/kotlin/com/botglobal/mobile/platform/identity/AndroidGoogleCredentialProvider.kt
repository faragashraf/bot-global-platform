package com.botglobal.mobile.platform.identity

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class AndroidGoogleCredentialProvider(
    private val activity: ComponentActivity,
    private val serverClientId: String,
    private val credentialManager: CredentialManager = CredentialManager.create(activity),
) : FederatedCredentialProvider {
    override suspend fun acquire(provider: FederatedIdentityProvider): FederatedCredentialResult {
        if (provider != FederatedIdentityProvider.Google) return FederatedCredentialResult.Unavailable
        if (serverClientId.isBlank()) return FederatedCredentialResult.ConfigurationMissing

        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (
                credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                FederatedCredentialResult.Failed
            } else {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                FederatedCredentialResult.Acquired(
                    FederatedCredential(
                        provider = FederatedIdentityProvider.Google,
                        type = FederatedCredentialType.IdToken,
                        value = google.idToken,
                    ),
                )
            }
        } catch (_: GetCredentialCancellationException) {
            FederatedCredentialResult.Cancelled
        } catch (_: NoCredentialException) {
            FederatedCredentialResult.Unavailable
        } catch (_: Exception) {
            FederatedCredentialResult.Failed
        }
    }
}
