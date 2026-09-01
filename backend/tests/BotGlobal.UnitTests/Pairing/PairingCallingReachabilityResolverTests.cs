using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Application.Calling;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PairingCallingReachabilityResolverTests
{
    [Fact]
    public async Task Only_active_same_application_device_and_push_registration_make_a_participant_reachable()
    {
        var applicationId = Guid.NewGuid();
        var otherApplicationId = Guid.NewGuid();
        await using var db = CreateContext();
        var reachable = Participant("reachable", true);
        var invalidated = Participant("invalidated", true);
        var crossApplication = Participant("cross", true);
        var inactiveMembership = Participant("inactive", false);
        AddDeviceWithRegistration(db, applicationId, reachable.SubjectId);
        var invalidRegistration = AddDeviceWithRegistration(db, applicationId, invalidated.SubjectId);
        invalidRegistration.Registration.Invalidate(DateTimeOffset.UtcNow);
        AddDeviceWithRegistration(db, otherApplicationId, crossApplication.SubjectId);
        AddDeviceWithRegistration(db, applicationId, inactiveMembership.SubjectId);
        await db.SaveChangesAsync();
        var resolver = new PairingCallingReachabilityResolver(db, new Applications(applicationId));

        var result = await resolver.FindReachableMembershipsAsync(
            "nqrb", [reachable, invalidated, crossApplication, inactiveMembership], default);

        Assert.Equal([reachable.MembershipId], result);
    }

    [Fact]
    public async Task Revoked_device_and_inactive_application_fail_closed()
    {
        var applicationId = Guid.NewGuid();
        await using var db = CreateContext();
        var participant = Participant("participant", true);
        var device = AddDeviceWithRegistration(db, applicationId, participant.SubjectId).MobileDevice;
        device.Revoke(DateTimeOffset.UtcNow);
        await db.SaveChangesAsync();

        var revokedResult = await new PairingCallingReachabilityResolver(db, new Applications(applicationId))
            .FindReachableMembershipsAsync("nqrb", [participant], default);
        var inactiveResult = await new PairingCallingReachabilityResolver(db, new Applications(applicationId, false))
            .FindReachableMembershipsAsync("nqrb", [participant], default);

        Assert.Empty(revokedResult);
        Assert.Empty(inactiveResult);
    }

    private static CallingParticipantDescriptor Participant(string subject, bool active) =>
        new(Guid.NewGuid(), "nqrb", subject, subject, active);

    private static (MobileDevice MobileDevice, MobilePushRegistration Registration) AddDeviceWithRegistration(
        PairingDbContext db,
        Guid applicationId,
        string subject)
    {
        var device = new MobileDevice(Guid.NewGuid(), applicationId, subject, $"installation-{Guid.NewGuid():N}",
            "android", "Test device", "1.0", [1, 2, 3, 4], DateTimeOffset.UtcNow);
        var registration = new MobilePushRegistration(device.Id, "fcm", $"synthetic-{Guid.NewGuid():N}", DateTimeOffset.UtcNow);
        db.Devices.Add(device);
        db.PushRegistrations.Add(registration);
        return (device, registration);
    }

    private static PairingDbContext CreateContext() => new(
        new DbContextOptionsBuilder<PairingDbContext>()
            .UseInMemoryDatabase($"calling-reachability-{Guid.NewGuid():N}").Options);

    private sealed class Applications(Guid applicationId, bool active = true) : IPlatformClientApplicationResolver
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(string clientKey, CancellationToken cancellationToken) =>
            Task.FromResult<PlatformClientDescriptor?>(new(applicationId, clientKey, "Application", active));
    }
}
