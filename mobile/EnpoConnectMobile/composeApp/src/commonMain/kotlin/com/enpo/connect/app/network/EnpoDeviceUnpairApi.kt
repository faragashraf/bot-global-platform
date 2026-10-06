package com.enpo.connect.app.network

import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.enpo.connect.app.pairing.EnpoDeviceUnpairClient
import com.enpo.connect.app.pairing.EnpoDeviceUnpairResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

class EnpoDeviceUnpairApi(
    private val client: HttpClient,
    private val configuration: EnpoNetworkConfiguration,
) : EnpoDeviceUnpairClient {
    override suspend fun revoke(credential: MobileDeviceCredential): EnpoDeviceUnpairResponse = try {
        val response = client.post(configuration.endpoint(EnpoPublicServiceRoute.DeviceUnpair)) {
            header(HttpHeaders.Authorization, "Device ${credential.credential}")
        }
        when {
            response.status == HttpStatusCode.NoContent -> EnpoDeviceUnpairResponse.Revoked
            response.status == HttpStatusCode.Unauthorized -> EnpoDeviceUnpairResponse.AlreadyInvalid
            else -> EnpoDeviceUnpairResponse.Unavailable
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        EnpoDeviceUnpairResponse.Unavailable
    } catch (_: ConnectTimeoutException) {
        EnpoDeviceUnpairResponse.Unavailable
    } catch (_: Exception) {
        EnpoDeviceUnpairResponse.Unavailable
    }
}
