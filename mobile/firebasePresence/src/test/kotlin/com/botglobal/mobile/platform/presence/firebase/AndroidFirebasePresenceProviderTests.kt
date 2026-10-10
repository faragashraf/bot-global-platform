package com.botglobal.mobile.platform.presence.firebase

import com.botglobal.mobile.platform.presence.PresenceAccount
import com.botglobal.mobile.platform.presence.PresenceGatewayEvent
import com.botglobal.mobile.platform.presence.PresenceLease
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidFirebasePresenceProviderTests {
    @Test
    fun configuration_rejects_unrelated_hosts_namespaces_ports_and_loopback() {
        val valid = configuration()

        assertFailsWith<IllegalArgumentException> {
            valid.copy(databaseUrl = "https://attacker.example/", allowedDatabaseHost = "attacker.example").requireValid()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(databaseNamespace = "other-project-default-rtdb").requireValid()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(
                databaseUrl = "https://approved-project-default-rtdb.firebaseio.com:444/",
                allowedDatabaseHost = "approved-project-default-rtdb.firebaseio.com",
            ).requireValid()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(databaseUrl = "https://127.0.0.1/", allowedDatabaseHost = "127.0.0.1").requireValid()
        }
    }

    @Test
    fun activation_never_reports_connected_before_the_driver_acknowledges_registration_and_publish() = runTest {
        val factory = FakeFactory()
        val provider = AndroidFirebasePresenceProvider(
            configuration(), FakeBackend(), factory, backgroundScope,
        )
        val connected = async { provider.events.first() }
        runCurrent()

        provider.activate(account("member"), generation = 1)

        assertFalse(connected.isCompleted)
        factory.callback(PresenceDriverEvent.connected(50))
        assertEquals(PresenceGatewayEvent.Connected(1, 50), connected.await())
        assertEquals(listOf("connect:lease-member:1"), factory.order)
    }

    @Test
    fun account_replacement_closes_and_invalidates_the_prior_generation() = runTest {
        val backend = FakeBackend()
        val factory = FakeFactory()
        val provider = AndroidFirebasePresenceProvider(configuration(), backend, factory, backgroundScope)

        provider.activate(account("first"), generation = 1)
        provider.activate(account("second"), generation = 2)

        assertTrue(factory.connections.first().closed)
        assertEquals(listOf("lease-first"), backend.invalidated)
        assertFalse(factory.connections.last().closed)
    }

    @Test
    fun background_suspend_reports_ambiguity_instead_of_phone_offline() = runTest {
        val factory = FakeFactory()
        val backend = FakeBackend()
        val provider = AndroidFirebasePresenceProvider(configuration(), backend, factory, backgroundScope)
        val lease = provider.activate(account("member"), generation = 3)
        val event = async { provider.events.first() }
        runCurrent()

        provider.suspend(lease, generation = 3)

        assertEquals(PresenceGatewayEvent.Unknown(3, "background_ambiguous"), event.await())
        assertTrue(factory.connections.single().closed)
        assertEquals(listOf("lease-member"), backend.invalidated)
    }

    @Test
    fun resume_bootstraps_a_new_lease_and_connection_instead_of_reusing_closed_auth_state() = runTest {
        val backend = FakeBackend()
        val factory = FakeFactory()
        val provider = AndroidFirebasePresenceProvider(configuration(), backend, factory, backgroundScope)
        val first = provider.activate(account("member"), generation = 4)
        provider.suspend(first, generation = 4)

        val replacement = provider.resume(first, generation = 4)

        assertEquals("lease-member-2", replacement.leaseId)
        assertEquals(2, factory.connections.size)
        assertTrue(factory.connections.first().closed)
        assertFalse(factory.connections.last().closed)
    }

    private fun account(member: String) = PresenceAccount("nqrb", member, "subject-$member")
    private fun configuration() = FirebasePresenceConfiguration(
        enabled = true,
        applicationKey = "nqrb",
        projectId = "approved-project",
        databaseNamespace = "approved-project-default-rtdb",
        databaseUrl = "https://approved-project-default-rtdb.firebaseio.com/",
        allowedDatabaseHost = "approved-project-default-rtdb.firebaseio.com",
        apiKey = "synthetic-api-key",
        applicationId = "1:1:android:synthetic",
    )

    private class FakeBackend : FirebasePresenceLeaseBackend {
        val invalidated = mutableListOf<String>()
        private var bootstrapCount = 0
        override suspend fun bootstrap(account: PresenceAccount): PresenceLease {
            bootstrapCount++
            val suffix = if (bootstrapCount == 1) "" else "-$bootstrapCount"
            return lease("lease-${account.membershipId}$suffix")
        }
        override suspend fun renew(account: PresenceAccount, leaseId: String) = lease("$leaseId-renewed")
        override suspend fun invalidate(account: PresenceAccount, leaseId: String) { invalidated += leaseId }
        private fun lease(id: String) = PresenceLease(
            id, "token", "https://presence.example/", "presenceConnections/uid/$id/connection",
            100_000, 20, 40,
        )
    }

    private class FakeFactory : FirebasePresenceDriverFactory {
        val order = mutableListOf<String>()
        val connections = mutableListOf<FakeConnection>()
        lateinit var callback: (FirebasePresenceDriverEvent) -> Unit

        override suspend fun connect(
            lease: PresenceLease,
            generation: Long,
            event: (FirebasePresenceDriverEvent) -> Unit,
        ): FirebasePresenceConnection {
            order += "connect:${lease.leaseId}:$generation"
            callback = event
            return FakeConnection().also(connections::add)
        }
    }

    private class FakeConnection : FirebasePresenceConnection {
        var closed = false
        override suspend fun heartbeat(generation: Long) = Unit
        override suspend fun resume(generation: Long) = Unit
        override suspend fun suspend(generation: Long) = Unit
        override suspend fun close(generation: Long) { closed = true }
    }

    private object PresenceDriverEvent {
        fun connected(at: Long): FirebasePresenceDriverEvent = FirebasePresenceDriverEvent.Connected(at)
    }
}
