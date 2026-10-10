using System.Security.Claims;
using BotGlobal.Communication.Application.Chat;
using BotGlobal.Communication.Domain.Chat;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatDomainTests
{
    [Fact]
    public void DirectPairKey_IsCanonicalUnambiguousAndOrdinal()
    {
        var first = ChatConversation.CreatePairKey("staff:1", "ab");
        Assert.Equal(first, ChatConversation.CreatePairKey("ab", "staff:1"));
        Assert.NotEqual(first, ChatConversation.CreatePairKey("staff", "1:ab"));
        Assert.NotEqual(ChatConversation.CreatePairKey("SUBJECT", "x"), ChatConversation.CreatePairKey("subject", "x"));
        Assert.Contains("staff:1", first, StringComparison.Ordinal);
    }

    [Fact]
    public void VoiceAck_IsRecipientBoundExactAndIdempotent()
    {
        var now = DateTimeOffset.Parse("2026-10-07T10:00:00Z");
        var transfer = new ChatVoiceTransfer(Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid(), "sender", "recipient",
            "opaque.m4a", new string('a', 64), 1234, 12_000, now, now.AddDays(7));
        Assert.False(transfer.Acknowledge("sender", Guid.NewGuid(), new string('a', 64), 1234, now));
        Assert.False(transfer.Acknowledge("recipient", Guid.NewGuid(), new string('b', 64), 1234, now));
        var installation = Guid.NewGuid();
        Assert.True(transfer.Acknowledge("recipient", installation, new string('a', 64), 1234, now));
        Assert.True(transfer.Acknowledge("recipient", installation, new string('a', 64), 1234, now.AddMinutes(1)));
        Assert.Equal(ChatVoiceTransferState.AcknowledgedDeletionPending, transfer.State);
        transfer.MarkDeleted(now.AddMinutes(2));
        Assert.Equal(ChatVoiceTransferState.AcknowledgedDeleted, transfer.State);
        Assert.False(transfer.IsDownloadable(now));
    }

    [Fact]
    public void ExpiredVoice_BecomesTerminalBeforePhysicalDeletion()
    {
        var now = DateTimeOffset.Parse("2026-10-07T10:00:00Z");
        var transfer = new ChatVoiceTransfer(Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid(), "one", "two",
            "opaque.m4a", new string('c', 64), 10, 1000, now.AddDays(-8), now.AddDays(-1));
        transfer.Expire(now);
        Assert.Equal(ChatVoiceTransferState.ExpiredDeletionPending, transfer.State);
        Assert.False(transfer.IsDownloadable(now));
        transfer.MarkDeleteFailure("chat_voice_delete_failed");
        Assert.Equal(ChatVoiceTransferState.ExpiredDeletionPending, transfer.State);
        Assert.Equal(1, transfer.DeleteAttempts);
    }

    [Fact]
    public async Task ActorResolver_RejectsMixedGuestAndMalformedSchemes()
    {
        var applicationId = Guid.NewGuid();
        var applications = new FakeApplications(new PlatformClientDescriptor(applicationId, "app-two", "App Two", true));
        var resolver = new ChatActorResolver(applications, applications);
        var session = new ClaimsIdentity([
            new Claim(ClaimTypes.NameIdentifier, "non-guid/subject"),
            new Claim(ApplicationIdentityDefaults.MembershipIdClaim, Guid.NewGuid().ToString()),
            new Claim(ApplicationIdentityDefaults.ApplicationKeyClaim, "app-two"),
            new Claim(ApplicationIdentityDefaults.GuestClaim, "false"),
        ], ApplicationIdentityDefaults.Scheme);
        var device = new ClaimsIdentity([
            new Claim(MobileDeviceAuthenticationDefaults.PlatformClientIdClaim, applicationId.ToString()),
            new Claim(MobileDeviceAuthenticationDefaults.DeviceIdClaim, Guid.NewGuid().ToString()),
            new Claim(MobileDeviceAuthenticationDefaults.ExternalSubjectIdClaim, "non-guid/subject"),
        ], MobileDeviceAuthenticationDefaults.Scheme);

        Assert.Null(await resolver.ResolveAsync(new ClaimsPrincipal([session, device]), default));
        Assert.Equal("non-guid/subject", (await resolver.ResolveAsync(new ClaimsPrincipal(session), default))!.SubjectId);
        var pairedActor = await resolver.ResolveAsync(new ClaimsPrincipal(device), default);
        Assert.NotNull(pairedActor);
        Assert.Equal(ChatActorMechanism.PairedDevice, pairedActor.Mechanism);
        Assert.Equal("non-guid/subject", pairedActor.SubjectId);

        var guest = new ClaimsIdentity(session.Claims.Where(x => x.Type != ApplicationIdentityDefaults.GuestClaim)
            .Append(new Claim(ApplicationIdentityDefaults.GuestClaim, "true")), ApplicationIdentityDefaults.Scheme);
        Assert.Null(await resolver.ResolveAsync(new ClaimsPrincipal(guest), default));
    }

    [Fact]
    public void Registry_RequiresExactlyOneDirectoryAndPolicy()
    {
        var descriptor = new PlatformClientDescriptor(Guid.NewGuid(), "app-two", "App Two", true);
        var applications = new FakeApplications(descriptor);
        Assert.Null(new ChatPolicyRegistry([], [], applications).Resolve("app-two"));
        var directory = new FakeDirectory("app-two");
        var policy = new FakePolicy("app-two");
        Assert.NotNull(new ChatPolicyRegistry([directory], [policy], applications).Resolve("app-two"));
        Assert.Null(new ChatPolicyRegistry([directory, directory], [policy], applications).Resolve("app-two"));
        Assert.Null(new ChatPolicyRegistry([directory], [policy], applications).Resolve("foreign-app"));
    }

    private sealed class FakeApplications(PlatformClientDescriptor descriptor) :
        IPlatformClientApplicationResolver, IPlatformClientDescriptorReader
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(string key, CancellationToken token) =>
            Task.FromResult<PlatformClientDescriptor?>(key == descriptor.ClientKey ? descriptor : null);
        public Task<PlatformClientDescriptor?> FindAsync(Guid id, CancellationToken token) =>
            Task.FromResult<PlatformClientDescriptor?>(id == descriptor.PlatformClientId ? descriptor : null);
    }
    private sealed class FakeDirectory(string key) : IChatParticipantDirectory
    {
        public string ApplicationKey => key;
        public Task<ChatParticipant?> FindByReferenceAsync(ChatApplication app, string value, CancellationToken token) => Task.FromResult<ChatParticipant?>(null);
        public Task<ChatParticipant?> FindBySubjectAsync(ChatApplication app, string value, CancellationToken token) => Task.FromResult<ChatParticipant?>(null);
    }
    private sealed class FakePolicy(string key) : IChatAccessPolicy
    {
        public string ApplicationKey => key;
        public Task<bool> CanStartDirectConversationAsync(ChatActor actor, ChatParticipant self, ChatParticipant counterpart, CancellationToken token) => Task.FromResult(false);
        public Task<bool> IsBidirectionallyBlockedAsync(ChatApplication app, ChatParticipant first, ChatParticipant second, CancellationToken token) => Task.FromResult(true);
    }
}
