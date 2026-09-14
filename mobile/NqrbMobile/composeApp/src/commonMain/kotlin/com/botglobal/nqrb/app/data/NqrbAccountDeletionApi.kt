package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

enum class NqrbAccountDeletionOutcome {
    Deleted,
    Accepted,
    AuthenticationRequired,
    RetryableFailure,
    Rejected,
}

fun interface NqrbAccountDeletionGateway {
    suspend fun deleteCurrentAccount(): NqrbAccountDeletionOutcome
}

object UnavailableNqrbAccountDeletionGateway : NqrbAccountDeletionGateway {
    override suspend fun deleteCurrentAccount() = NqrbAccountDeletionOutcome.RetryableFailure
}

class NqrbAccountDeletionApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val sessionVault: SessionVault,
) : NqrbAccountDeletionGateway {
    override suspend fun deleteCurrentAccount(): NqrbAccountDeletionOutcome {
        val session = sessionVault.restore()
            ?: return NqrbAccountDeletionOutcome.AuthenticationRequired
        return try {
            val response = client.delete(endpoint("/api/mobile/nqrb/account")) {
                bearerAuth(session.accessToken)
            }
            when {
                response.status == HttpStatusCode.NoContent -> NqrbAccountDeletionOutcome.Deleted
                response.status == HttpStatusCode.Accepted -> NqrbAccountDeletionOutcome.Accepted
                response.status == HttpStatusCode.Unauthorized -> NqrbAccountDeletionOutcome.AuthenticationRequired
                response.status == HttpStatusCode.TooManyRequests -> NqrbAccountDeletionOutcome.RetryableFailure
                response.status.value >= 500 -> NqrbAccountDeletionOutcome.RetryableFailure
                else -> NqrbAccountDeletionOutcome.Rejected
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbAccountDeletionOutcome.RetryableFailure
        }
    }

    private fun endpoint(path: String) = apiBaseUrl.trimEnd('/') + path
}
