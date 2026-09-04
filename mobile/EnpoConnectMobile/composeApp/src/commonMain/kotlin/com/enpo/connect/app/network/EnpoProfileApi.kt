package com.enpo.connect.app.network

import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import com.botglobal.mobile.platform.profile.ProfileFetchResult
import com.botglobal.mobile.platform.profile.ProfileRepository
import com.botglobal.mobile.platform.profile.ProfileSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

class EnpoProfileApi(
    private val client: HttpClient,
    private val configuration: EnpoNetworkConfiguration,
    private val credentialVault: MobileDeviceCredentialVault,
) : ProfileRepository {
    override suspend fun fetchMyProfile(): ProfileFetchResult {
        val credential = credentialVault.restore()
            ?: return ProfileFetchResult.AuthenticationRequired

        return try {
            val response = client.get(
                configuration.endpoint(EnpoPublicServiceRoute.Profile),
            ) {
                accept(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Device ${credential.credential}")
            }

            when {
                response.status.value in 200..299 -> {
                    val profile = response.body<ProfileResponse>()
                    ProfileFetchResult.Available(
                        ProfileSnapshot(
                            displayName = profile.displayName,
                            jobTitle = profile.jobTitle,
                            organizationUnit = profile.organizationUnit,
                            version = profile.version,
                            updatedAtUtc = profile.updatedAtUtc,
                        ),
                    )
                }
                response.status == HttpStatusCode.NotFound ->
                    ProfileFetchResult.NotAvailableYet
                response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.Forbidden ->
                    ProfileFetchResult.AuthenticationRequired
                else -> ProfileFetchResult.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProfileFetchResult.Failed
        }
    }

    override fun toString(): String =
        "EnpoProfileApi(endpoint=<redacted>,credential=<redacted>)"
}

@Serializable
private data class ProfileResponse(
    val displayName: String,
    val jobTitle: String? = null,
    val organizationUnit: String? = null,
    val version: Long,
    val updatedAtUtc: String,
)
