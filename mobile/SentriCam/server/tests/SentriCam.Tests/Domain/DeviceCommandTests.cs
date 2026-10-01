using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Domain;

public sealed class DeviceCommandTests
{
    [Fact]
    public void QueueCapturesRetryMetadataWithoutBreakingLegacyFactory()
    {
        var device = DeviceTestFactory.CreateDevice();
        var expiresAt = DeviceTestFactory.Now.AddMinutes(5);

        var command = DeviceCommand.Queue(
            device.Id,
            DeviceCommandKind.StartRecording,
            "correlation-123",
            DeviceTestFactory.Now,
            expiresAt,
            DeviceCommandOrigin.Operator);

        Assert.NotEqual(Guid.Empty, command.CommandId);
        Assert.Equal(command.Id, command.CommandId);
        Assert.Equal("correlation-123", command.CorrelationId);
        Assert.Equal(DeviceTestFactory.Now, command.CreatedAtUtc);
        Assert.Equal(expiresAt, command.ExpiresAtUtc);
        Assert.Equal(DeviceCommandOrigin.Operator, command.Origin);
        Assert.False(command.IsExpiredAt(expiresAt.AddTicks(-1)));
        Assert.True(command.IsExpiredAt(expiresAt));

        var legacy = DeviceCommand.Queue(
            device.Id,
            DeviceCommandKind.StopRecording,
            "legacy",
            DeviceTestFactory.Now);
        Assert.Null(legacy.ExpiresAtUtc);
        Assert.Equal(DeviceCommandOrigin.Application, legacy.Origin);
    }

    [Fact]
    public void QueueRejectsExpirationThatCannotBeExecuted()
    {
        var device = DeviceTestFactory.CreateDevice();

        Assert.Throws<DomainValidationException>(() => DeviceCommand.Queue(
            device.Id,
            DeviceCommandKind.StartMonitoring,
            "expired",
            DeviceTestFactory.Now,
            DeviceTestFactory.Now,
            DeviceCommandOrigin.Automation));
    }

    [Fact]
    public void DeliveryAcknowledgementsAreIdempotent()
    {
        var command = DeviceCommand.Queue(
            DeviceTestFactory.CreateDevice().Id,
            DeviceCommandKind.RestartMonitoring,
            "retry-safe",
            DeviceTestFactory.Now);
        var dispatchedAt = DeviceTestFactory.Now.AddSeconds(1);
        var completedAt = DeviceTestFactory.Now.AddSeconds(2);

        command.MarkDispatched(dispatchedAt);
        command.MarkDispatched(dispatchedAt.AddSeconds(1));
        command.Succeed("accepted", null, completedAt);
        command.Succeed("accepted", null, completedAt.AddSeconds(1));

        Assert.Equal(dispatchedAt, command.DispatchedAtUtc);
        Assert.Equal(completedAt, command.CompletedAtUtc);
        Assert.Equal(DeviceCommandState.Succeeded, command.State);
    }
}
