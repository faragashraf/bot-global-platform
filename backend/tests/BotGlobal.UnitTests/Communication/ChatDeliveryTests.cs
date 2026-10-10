using System.Security.Claims;
using BotGlobal.Communication.Application.Chat;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Communication.Domain.Chat;
using BotGlobal.Communication.Hubs;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatDeliveryTests
{
    [Fact]
    public async Task RevokedAndExpiredSocketGetsNoHintWhileValidSameSubjectDoes()
    {
        var actor = new ChatActor(new(Guid.NewGuid(), "test-app"), "staff:recipient", ChatActorMechanism.ApplicationSession);
        var registry = new ChatConnectionRegistry(); var hub = new Hub(); var clock = new Clock();
        var deadline = clock.GetUtcNow().AddSeconds(1); var revoked = false; var aborted = 0;
        registry.Add("revoked", actor, new(), new((_, _) => Task.FromResult(!revoked)), () => aborted++);
        registry.Add("expired", actor, new(), new((_, _) => Task.FromResult(clock.GetUtcNow() < deadline)), () => aborted++);
        registry.Add("valid", actor, new(), new((_, _) => Task.FromResult(true)), () => aborted++);
        registry.Add("other-app", actor with { Application = new(Guid.NewGuid(), "other") }, new(), new((_, _) => Task.FromResult(true)), () => aborted++);
        using var services = new ServiceCollection().AddSingleton<IChatActorResolver>(new Actor(actor))
            .AddSingleton(new ChatPolicyRegistry([new Directory()], [new Policy()], new Applications())).BuildServiceProvider();
        revoked = true; clock.Now += TimeSpan.FromSeconds(2);
        await registry.SendAsync(new(actor.Application.ApplicationId, Guid.NewGuid(), Guid.NewGuid(), 1, "text", clock.Now), actor.SubjectId, services, hub, default);
        Assert.Equal(["valid"], hub.ClientsImpl.Sent); Assert.Equal(2, aborted);
    }

    [Fact]
    public async Task DispatcherDrainsBeyondFiftyAndKeepsApplicationsIsolated()
    {
        await using var f = await Fixture.Create(61);
        await f.Processor().ProcessAsync(default);
        Assert.Equal(122, f.Push.Calls.Count);
        Assert.All(f.Push.Calls, call => Assert.Equal(f.ApplicationId, call.Application.ApplicationId));
        Assert.All(await f.Db.ChatDispatches.ToListAsync(), row => Assert.Equal(ChatDispatchState.Delivered, row.State));
    }

    [Fact]
    public async Task ConcurrentProcessorCannotSendClaimedDispatch()
    {
        await using var f = await Fixture.Create(1);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Push.Before = async () => { entered.TrySetResult(); await release.Task; };
        var first = f.Processor().ProcessAsync(default); await entered.Task;
        await using var secondDb = new CommunicationDbContext(f.Options);
        await f.Processor(secondDb).ProcessAsync(default);
        Assert.Single(f.Push.Calls); release.SetResult(); await first;
        Assert.Equal(2, f.Push.Calls.Count);
    }

    [Fact]
    public async Task AcceptedDeviceIsNotRetriedWhenAnotherDeviceHasTransientFailure()
    {
        await using var f = await Fixture.Create(1);
        f.Push.NextKind = ApplicationPushDispatchKind.TransientFailure;
        await f.Processor().ProcessAsync(default);
        var failedToken = f.Push.Calls.First().RegistrationToken;
        f.Clock.Now += TimeSpan.FromMinutes(1);
        await f.Processor().ProcessAsync(default);
        Assert.Equal(3, f.Push.Calls.Count);
        Assert.Equal(2, f.Push.Calls.Count(x => x.RegistrationToken == failedToken));
        Assert.Equal(ChatDispatchState.Delivered, (await f.Db.ChatDispatches.SingleAsync()).State);
    }

    [Fact]
    public async Task UnknownSendOutcomeAfterRestartIsNotBlindlyRepeated()
    {
        await using var f = await Fixture.Create(1); f.Push.ThrowAfterSend = true;
        await f.Processor().ProcessAsync(default);
        var uncertainToken = f.Push.Calls.Single().RegistrationToken;
        f.Clock.Now += TimeSpan.FromMinutes(1);
        await using var restarted = new CommunicationDbContext(f.Options);
        await f.Processor(restarted).ProcessAsync(default);
        Assert.Single(f.Push.Calls, x => x.RegistrationToken == uncertainToken);
        Assert.Equal(ChatDispatchState.Terminal, (await restarted.ChatDispatches.SingleAsync()).State);
    }

    [Fact]
    public async Task PermanentProviderOutcomeIsTerminalAndNeverFallsBackToAnotherApplication()
    {
        await using var f = await Fixture.Create(1); f.Push.NextKind = ApplicationPushDispatchKind.PermanentFailure;
        await f.Processor().ProcessAsync(default); f.Clock.Now += TimeSpan.FromDays(1); await f.Processor().ProcessAsync(default);
        Assert.Equal(2, f.Push.Calls.Count);
        Assert.All(f.Push.Calls, call => Assert.Equal(f.ApplicationId, call.Application.ApplicationId));
        Assert.Equal(ChatDispatchState.Terminal, (await f.Db.ChatDispatches.SingleAsync()).State);
    }

    [Fact]
    public async Task ChatPushCarriesConversationDestinationAndSenderCopy()
    {
        await using var f = await Fixture.Create(1);

        await f.Processor().ProcessAsync(default);

        Assert.Equal(2, f.Push.Calls.Count);
        Assert.All(f.Push.Calls, call =>
        {
            Assert.Equal(ChatContract.MessageEvent, call.Data["type"]);
            Assert.Equal(f.ConversationId.ToString("D"), call.Data["conversationId"]);
            Assert.Equal($"chat:{f.ConversationId:D}", call.Data["destination"]);
            Assert.Equal("Synthetic", call.Title);
            Assert.Equal("synthetic", call.Body);
            Assert.Equal("Synthetic", call.Data["titleEn"]);
            Assert.Equal("New message", call.Data["bodyEn"]);
        });
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public Guid ApplicationId { get; } = Guid.NewGuid();
        public Guid ConversationId { get; private set; }
        public required SqliteConnection Connection { get; init; }
        public required DbContextOptions<CommunicationDbContext> Options { get; init; }
        public required CommunicationDbContext Db { get; init; }
        public Clock Clock { get; } = new(); public Provider Push { get; } = new();
        private readonly Routes _routes = new(); private readonly Hub _hub = new();
        public ChatDispatchProcessor Processor(CommunicationDbContext? db = null) => new(db ?? Db,
            new ChatPolicyRegistry([new Directory()], [new Policy()], new Applications()), _routes, _routes, Push,
            new ChatConnectionRegistry(), _hub, new ServiceCollection().BuildServiceProvider(), Clock);
        public static async Task<Fixture> Create(int count)
        {
            var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
            connection.CreateCollation("Latin1_General_100_BIN2", StringComparer.Ordinal.Compare);
            var options = new DbContextOptionsBuilder<CommunicationDbContext>().UseSqlite(connection).ReplaceService<IModelCustomizer, SqliteChatModelCustomizer>().Options;
            var db = new CommunicationDbContext(options); await db.Database.EnsureCreatedAsync();
            var f = new Fixture { Connection = connection, Options = options, Db = db };
            var conversation = new ChatConversation(f.ApplicationId, "sender", "recipient", f.Clock.Now); db.ChatConversations.Add(conversation);
            f.ConversationId = conversation.Id;
            for (var i = 0; i < count; i++) {
                var message = new ChatMessage(f.ApplicationId, conversation.Id, conversation.AllocateSequence(f.Clock.Now), "sender", $"client-{i}", ChatMessageKind.Text, ChatMessage.FingerprintText("synthetic"), f.Clock.Now, "synthetic");
                db.ChatMessages.Add(message); db.ChatDispatches.Add(new(f.ApplicationId, message.Id, "recipient", f.Clock.Now));
            }
            await db.SaveChangesAsync(); db.ChangeTracker.Clear(); return f;
        }
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await Connection.DisposeAsync(); }
    }
    private sealed class Provider : IApplicationPushNotificationDispatcher {
        public List<ApplicationPushMessage> Calls { get; } = []; public ApplicationPushDispatchKind NextKind = ApplicationPushDispatchKind.Accepted;
        public bool ThrowAfterSend; public Func<Task> Before = () => Task.CompletedTask;
        public async Task<ApplicationPushDispatchResult> DispatchAsync(ApplicationPushMessage message, CancellationToken token) {
            Calls.Add(message); await Before(); if (ThrowAfterSend) { ThrowAfterSend = false; throw new IOException("synthetic failure after send"); }
            var kind = NextKind; NextKind = ApplicationPushDispatchKind.Accepted; return new(kind);
        }
    }
    private sealed class Routes : IMobileRecipientResolver, IMobilePushDestinationResolver {
        private readonly Guid[] _devices = [Guid.NewGuid(), Guid.NewGuid()];
        public Task<IReadOnlyList<MobileRecipientDevice>> ResolveActiveDevicesAsync(NotificationApplicationContext app, string subject, CancellationToken token) => Task.FromResult<IReadOnlyList<MobileRecipientDevice>>(_devices.Select(x => new MobileRecipientDevice(x, "synthetic", "android", null)).ToArray());
        public Task<MobilePushDestination?> ResolveActiveAsync(NotificationApplicationContext app, Guid device, string provider, CancellationToken token) => Task.FromResult<MobilePushDestination?>(new(device, provider, device.ToString()));
    }
    private sealed class Applications : IPlatformClientDescriptorReader { public Task<PlatformClientDescriptor?> FindAsync(Guid id, CancellationToken token) => Task.FromResult<PlatformClientDescriptor?>(new(id, "test-app", "Test", true)); }
    private sealed class Directory : IChatParticipantDirectory {
        public string ApplicationKey => "test-app";
        public Task<ChatParticipant?> FindBySubjectAsync(ChatApplication app, string subject, CancellationToken token) => Task.FromResult<ChatParticipant?>(new(subject, subject, "Synthetic"));
        public Task<ChatParticipant?> FindByReferenceAsync(ChatApplication app, string reference, CancellationToken token) => FindBySubjectAsync(app, reference, token);
    }
    private sealed class Policy : IChatAccessPolicy {
        public string ApplicationKey => "test-app";
        public Task<bool> CanStartDirectConversationAsync(ChatActor actor, ChatParticipant self, ChatParticipant other, CancellationToken token) => Task.FromResult(true);
        public Task<bool> IsBidirectionallyBlockedAsync(ChatApplication app, ChatParticipant self, ChatParticipant other, CancellationToken token) => Task.FromResult(false);
    }
    private sealed class Actor(ChatActor actor) : IChatActorResolver { public Task<ChatActor?> ResolveAsync(ClaimsPrincipal principal, CancellationToken token) => Task.FromResult<ChatActor?>(actor); }
    private sealed class Clock : TimeProvider { public DateTimeOffset Now = DateTimeOffset.Parse("2026-10-07T00:00:00Z"); public override DateTimeOffset GetUtcNow() => Now; }
    private sealed class Hub : IHubContext<ChatHub> {
        public RecordingClients ClientsImpl { get; } = new(); public IHubClients Clients => ClientsImpl; public IGroupManager Groups { get; } = new Groups();
    }
    private sealed class RecordingClients : IHubClients {
        public List<string> Sent { get; } = [];
        public IClientProxy Client(string id) => new Proxy(() => Sent.Add(id));
        public IClientProxy All => throw new InvalidOperationException("Unvalidated broadcast");
        public IClientProxy AllExcept(IReadOnlyList<string> ids) => All; public IClientProxy Clients(IReadOnlyList<string> ids) => All;
        public IClientProxy Group(string name) => All; public IClientProxy GroupExcept(string name, IReadOnlyList<string> ids) => All;
        public IClientProxy Groups(IReadOnlyList<string> names) => All; public IClientProxy User(string id) => All; public IClientProxy Users(IReadOnlyList<string> ids) => All;
    }
    private sealed class Proxy(Action sent) : IClientProxy { public Task SendCoreAsync(string method, object?[] args, CancellationToken token = default) { sent(); return Task.CompletedTask; } }
    private sealed class Groups : IGroupManager {
        public Task AddToGroupAsync(string id, string group, CancellationToken token = default) => Task.CompletedTask;
        public Task RemoveFromGroupAsync(string id, string group, CancellationToken token = default) => Task.CompletedTask;
    }
}
