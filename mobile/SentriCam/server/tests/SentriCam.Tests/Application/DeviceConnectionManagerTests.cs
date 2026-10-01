using SentriCam.Application.Connections;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class DeviceConnectionManagerTests
{
    [Fact]
    public void ConnectingSameDeviceReplacesCurrentEntryWithoutDuplicates()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var manager = new DeviceConnectionManager(new FixedTimeProvider(DeviceTestFactory.Now.AddSeconds(2)));

        var first = manager.Connected(deviceId, "connection-1", DeviceTestFactory.Now);
        var duplicate = manager.Connected(deviceId, "connection-1", DeviceTestFactory.Now.AddSeconds(1));
        var replacement = manager.Connected(deviceId, "connection-2", DeviceTestFactory.Now.AddSeconds(2));

        Assert.False(first.IsReconnect);
        Assert.False(duplicate.IsReconnect);
        Assert.True(replacement.IsReconnect);
        Assert.Equal("connection-1", replacement.ReplacedConnectionId);
        Assert.Equal("connection-2", manager.GetStatus(deviceId)!.ConnectionId);
    }

    [Fact]
    public void StaleDisconnectCannotOverwriteReplacementConnection()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var manager = new DeviceConnectionManager(new FixedTimeProvider(DeviceTestFactory.Now.AddSeconds(3)));
        manager.Connected(deviceId, "connection-1", DeviceTestFactory.Now);
        manager.Connected(deviceId, "connection-2", DeviceTestFactory.Now.AddSeconds(1));

        var stale = manager.Disconnected("connection-1", DeviceTestFactory.Now.AddSeconds(2));
        var heartbeat = manager.Heartbeat(deviceId, "connection-2", DeviceTestFactory.Now.AddSeconds(3));

        Assert.Null(stale);
        Assert.NotNull(heartbeat);
        Assert.Equal(DeviceTransportState.Connected, manager.GetStatus(deviceId)!.State);
    }

    [Fact]
    public void TransportLossEntersRecoveringAndPreservesLifecycleTimestamps()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var manager = new DeviceConnectionManager(new FixedTimeProvider(DeviceTestFactory.Now.AddSeconds(30)));
        manager.Connected(deviceId, "connection-1", DeviceTestFactory.Now);
        var heartbeatAt = DeviceTestFactory.Now.AddSeconds(15);
        var disconnectedAt = DeviceTestFactory.Now.AddSeconds(30);

        var heartbeat = manager.Heartbeat(deviceId, "connection-1", heartbeatAt);
        var disconnected = manager.Disconnected("connection-1", disconnectedAt);
        var disconnectedStatus = Assert.IsType<DeviceConnectionStatus>(disconnected);

        Assert.Equal(heartbeatAt, heartbeat!.LastHeartbeatAtUtc);
        Assert.Equal(heartbeatAt, disconnectedStatus.LastHeartbeatAtUtc);
        Assert.Equal(disconnectedAt, disconnectedStatus.DisconnectedAtUtc);
        Assert.Equal(DeviceTransportState.Recovering, disconnectedStatus.State);
    }

    [Fact]
    public void ReconnectBeforePresenceTimeoutNeverEntersOffline()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var manager = new DeviceConnectionManager(clock);
        manager.Connected(deviceId, "connection-1", clock.Value);

        clock.Value = clock.Value.AddSeconds(2);
        Assert.Equal(
            DeviceTransportState.Recovering,
            manager.Disconnected("connection-1", clock.Value)!.State);

        clock.Value = clock.Value.AddSeconds(8);
        var reconnected = manager.Connected(deviceId, "connection-2", clock.Value);

        Assert.True(reconnected.IsReconnect);
        Assert.Equal(DeviceTransportState.Connected, manager.GetStatus(deviceId)!.State);
        Assert.Null(manager.GetStatus(deviceId)!.DisconnectedAtUtc);
    }

    [Fact]
    public void TransportLossBecomesOfflineOnlyAfterPresenceTimeout()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var manager = new DeviceConnectionManager(clock);
        manager.Connected(deviceId, "connection-1", clock.Value);
        clock.Value = clock.Value.AddSeconds(2);
        manager.Disconnected("connection-1", clock.Value);

        clock.Value = DeviceTestFactory.Now.AddSeconds(24);
        Assert.Equal(DeviceTransportState.Recovering, manager.GetStatus(deviceId)!.State);

        clock.Value = DeviceTestFactory.Now.AddSeconds(25);
        Assert.Equal(DeviceTransportState.Disconnected, manager.GetStatus(deviceId)!.State);
    }

    [Fact]
    public void MissingOptInTransportPulseEntersRecoveringWithinTwoSeconds()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var manager = new DeviceConnectionManager(clock);
        manager.Connected(deviceId, "connection-1", clock.Value);
        manager.TransportPulse(deviceId, "connection-1", clock.Value);

        clock.Value = clock.Value.AddSeconds(1);
        Assert.Equal(DeviceTransportState.Connected, manager.GetStatus(deviceId)!.State);

        clock.Value = clock.Value.AddSeconds(1);
        var recovering = manager.GetStatus(deviceId)!;
        Assert.Equal(DeviceTransportState.Recovering, recovering.State);
        Assert.Equal(clock.Value, recovering.DisconnectedAtUtc);
    }

    [Fact]
    public void LegacyConnectionWithoutTransportPulseUsesPresenceTimeout()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var manager = new DeviceConnectionManager(clock);
        manager.Connected(deviceId, "legacy-connection", clock.Value);

        clock.Value = clock.Value.AddSeconds(10);

        Assert.Equal(DeviceTransportState.Connected, manager.GetStatus(deviceId)!.State);
    }

    [Fact]
    public void RepeatedTransportInterruptionsReplaceOneConnectionWithoutGettingStuck()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var manager = new DeviceConnectionManager(clock);

        for (var cycle = 1; cycle <= 3; cycle++)
        {
            var connectionId = $"connection-{cycle}";
            manager.Connected(deviceId, connectionId, clock.Value);
            clock.Value = clock.Value.AddSeconds(2);
            Assert.Equal(DeviceTransportState.Recovering, manager.Disconnected(connectionId, clock.Value)!.State);
            clock.Value = clock.Value.AddSeconds(2);
        }

        manager.Connected(deviceId, "connection-final", clock.Value);
        Assert.Equal(DeviceTransportState.Connected, manager.GetStatus(deviceId)!.State);
    }

    [Fact]
    public void MissingTwoHeartbeatsIsReportedOfflineAndAHealthyHeartbeatRestoresConnected()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var clock = new SentriCam.Tests.TestDoubles.MutableTimeProvider(DeviceTestFactory.Now);
        var manager = new DeviceConnectionManager(clock);
        manager.Connected(deviceId, "connection-1", clock.GetUtcNow());

        clock.Value = DeviceTestFactory.Now.AddSeconds(26);
        var stale = manager.GetStatus(deviceId);

        Assert.Equal(DeviceTransportState.Disconnected, stale!.State);
        Assert.Equal(DeviceTestFactory.Now.AddSeconds(25), stale.DisconnectedAtUtc);

        var heartbeat = manager.Heartbeat(deviceId, "connection-1", clock.GetUtcNow());

        Assert.NotNull(heartbeat);
        Assert.Equal(DeviceTransportState.Connected, manager.GetStatus(deviceId)!.State);
    }

    [Fact]
    public void PresencePolicyMarksOfflineAtTwentyFiveSecondsWithoutFlickeringEarlier()
    {
        var deviceId = DeviceTestFactory.CreateDevice().Id;
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var manager = new DeviceConnectionManager(clock);
        manager.Connected(deviceId, "connection-1", clock.Value);

        clock.Value = clock.Value.AddSeconds(24);
        Assert.Equal(DeviceTransportState.Connected, manager.GetStatus(deviceId)!.State);

        clock.Value = clock.Value.AddSeconds(1);
        Assert.Equal(DeviceTransportState.Disconnected, manager.GetStatus(deviceId)!.State);
        Assert.Equal(TimeSpan.FromSeconds(1), DevicePresencePolicy.SweepInterval);
        Assert.Equal(TimeSpan.FromSeconds(30), DevicePresencePolicy.HealthFreshness);
        Assert.Equal(TimeSpan.FromSeconds(2), DevicePresencePolicy.TransportLostAfter);
    }

}
