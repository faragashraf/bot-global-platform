package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class NqrbCallingDirectoryApiTests {
    @Test
    fun maps_membership_and_display_from_the_same_api_participant() = runTest {
        val engine = MockEngine { request ->
            assertEquals(
                "https://api.example/api/mobile/calling/participants",
                request.url.toString(),
            )
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
            respond(
                content = """[{"membershipId":"remote-member","displayName":"Remote user","availability":"Reachable"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = NqrbCallingDirectoryApi(
            HttpClient(engine),
            "https://api.example/",
            FixedSessionVault(session()),
        )

        val result = api.loadCallableParticipants()

        assertEquals(1, result.size)
        assertEquals("remote-member", result.single().membershipId)
        assertEquals("Remote user", result.single().displayName)
        assertEquals(CallingParticipantAvailability.Reachable, result.single().availability)
    }

    @Test
    fun missing_session_does_not_issue_an_unauthenticated_directory_request() = runTest {
        var requests = 0
        val api = NqrbCallingDirectoryApi(
            HttpClient(MockEngine {
                requests++
                respondError(HttpStatusCode.InternalServerError)
            }),
            "https://api.example",
            FixedSessionVault(null),
        )

        assertFailsWith<NqrbCallingDirectoryAuthenticationException> {
            api.loadCallableParticipants()
        }
        assertEquals(0, requests)
    }

    @Test
    fun non_success_response_is_an_explicit_directory_failure() = runTest {
        val api = NqrbCallingDirectoryApi(
            HttpClient(MockEngine { respondError(HttpStatusCode.Unauthorized) }),
            "https://api.example",
            FixedSessionVault(session()),
        )

        val error = assertFailsWith<NqrbCallingDirectoryRequestException> {
            api.loadCallableParticipants()
        }

        assertEquals(HttpStatusCode.Unauthorized.value, error.statusCode)
    }

    private class FixedSessionVault(
        private val restored: MobileSession?,
    ) : SessionVault {
        override suspend fun restore() = restored
        override suspend fun save(session: MobileSession) = Unit
        override suspend fun clear() = Unit
    }

    private fun session() = MobileSession(
        accessToken = "access-token",
        accessExpiresAtUtc = "2099-01-01T00:00:00Z",
        refreshToken = "refresh-token",
        refreshExpiresAtUtc = "2099-02-01T00:00:00Z",
        identity = ApplicationIdentity(
            membershipId = "self-member",
            subjectId = "subject",
            displayName = "Current user",
            kind = IdentityKind.Registered,
            applicationKey = "nqrb",
        ),
    )
}
