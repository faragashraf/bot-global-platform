package com.ashraffarag.sentricam.device.command

import com.ashraffarag.sentricam.communication.signalr.RemoteDeviceCommand
import com.ashraffarag.sentricam.communication.signalr.SignalRClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMonitoringCommandExecutorTest {
    @Test
    fun supportedWireCommandsReuseDeviceCommandHandler() = runBlocking {
        val observed = mutableListOf<DeviceCommandRequest>()
        val executor = RemoteMonitoringCommandExecutor(
            handler = handler { request ->
                observed += request
                DeviceCommandResult.Accepted
            },
            clock = SignalRClock { NOW },
        )

        listOf(1, 2, 6, 7).forEach { commandType ->
            val result = executor.execute(command(commandType))
            assertEquals(1, result.outcome)
            assertNull(result.snapshot)
        }

        assertEquals(
            listOf(
                DeviceCommand.StartMonitoring,
                DeviceCommand.StopMonitoring,
                DeviceCommand.Ping,
                DeviceCommand.GetStatus,
            ),
            observed.map(DeviceCommandRequest::command),
        )
        assertEquals(List(4) { COMMAND_ID }, observed.map(DeviceCommandRequest::commandId))
    }

    @Test
    fun commandExecutionFailureReturnsSafeTypedFailure() = runBlocking {
        val executor = RemoteMonitoringCommandExecutor(
            handler = handler { error("secret failure detail") },
            clock = SignalRClock { NOW },
        )

        val result = executor.execute(command(6))

        assertEquals(2, result.outcome)
        assertEquals("command_execution_failed", result.resultCode)
    }

    @Test
    fun unsupportedWireCommandNeverReachesBusinessHandler() = runBlocking {
        var calls = 0
        val executor = RemoteMonitoringCommandExecutor(
            handler = handler {
                calls++
                DeviceCommandResult.Accepted
            },
            clock = SignalRClock { NOW },
        )

        val result = executor.execute(command(3))

        assertEquals(0, calls)
        assertEquals(2, result.outcome)
        assertEquals("unsupported_remote_command_not_available", result.resultCode)
    }

    private fun command(type: Int) = RemoteDeviceCommand(
        commandId = COMMAND_ID,
        deviceId = DEVICE_ID,
        commandType = type,
        correlationId = "dashboard-test",
        requestedAtUtc = "2026-08-01T00:00:00Z",
    )

    private fun handler(
        block: suspend (DeviceCommandRequest) -> DeviceCommandResult,
    ): DeviceCommandHandler = object : DeviceCommandHandler {
        override suspend fun handle(request: DeviceCommandRequest): DeviceCommandResult = block(request)
    }

    private companion object {
        const val COMMAND_ID = "9766906b-0303-4ca2-b6ac-9b247a754f4f"
        const val DEVICE_ID = "f16b4563-4c6c-48e2-b4fa-84e606613a47"
        const val NOW = 1_785_499_200_000L
    }
}
