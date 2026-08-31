using SentriCam.Contracts.Audit;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Contracts;

public sealed class AuditContractTests
{
    [Fact]
    public void AuditEntryCapturesActorSourceSeverityAndCorrelation()
    {
        var actor = new AuditActor("device-1", AuditActorType.Device, "Front Door");
        var entry = new AuditEntry(
            Guid.NewGuid(),
            DeviceTestFactory.Now,
            "device.registration.completed",
            actor,
            AuditSource.Api,
            AuditSeverity.Information,
            "Device",
            "device-1",
            "correlation-1",
            "{}");

        Assert.Equal(AuditActorType.Device, entry.Actor.ActorType);
        Assert.Equal(AuditSource.Api, entry.Source);
        Assert.Equal(AuditSeverity.Information, entry.Severity);
        Assert.Equal("correlation-1", entry.CorrelationId);
    }
}
