using System.Security.Claims;
using BotGlobal.Communication.Application.Chat;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.AspNetCore.Http;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using BotGlobal.Communication.Domain.Chat;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;
using System.Data.Common;
using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore.Infrastructure;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatEngineRegressionTests
{
    [Fact]
    public async Task IdenticalClientIdCannotReturnMessageFromAnotherConversation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.Engine.CreateOrGetDirectAsync("staff:beta", default);
        var second = await fixture.Engine.CreateOrGetDirectAsync("staff:gamma", default);
        Assert.NotNull(first); Assert.NotNull(second);
        var sent = await fixture.Engine.SendTextAsync(first.ConversationId, "retry-key", "same content", default);
        Assert.NotNull(sent.Message);
        var wrongTarget = await fixture.Engine.SendTextAsync(second.ConversationId, "retry-key", "same content", default);
        Assert.True(wrongTarget.Conflict, "The same key reused for a different target returned the original conversation message");
    }

    [Fact]
    public async Task MissingOrDuplicateAdapterDeniesReadAndAckEvenWithExistingData()
    {
        await using var f = await Fixture.CreateAsync();
        var conversation = (await f.Engine.CreateOrGetDirectAsync("staff:beta", default))!;
        Assert.NotNull((await f.Engine.SendTextAsync(conversation.ConversationId, "one", "hello", default)).Message);
        foreach (var directories in new IChatParticipantDirectory[][] { [], [new Directory("app-one"), new Directory("app-one")] })
        {
            var engine = f.NewEngine(new ChatPolicyRegistry(directories, [new Policy("app-one")], new ApplicationReader()));
            Assert.Null(await engine.AdvanceReadReceiptAsync(conversation.ConversationId, 1, default));
            Assert.False(await engine.AcknowledgeVoiceAsync(Guid.NewGuid(), f.Recipients.Device, new string('a', 64), 1, default));
        }
        Assert.Empty(await f.Db.ChatReceipts.ToListAsync());
    }

    [Fact]
    public async Task SameTimestampConversationPagesContainEveryIdExactlyOnce()
    {
        await using var f = await Fixture.CreateAsync();
        for (var i = 0; i < 65; i++) await f.Engine.CreateOrGetDirectAsync($"person:{i}", default);
        var found = new List<Guid>(); string? cursor = null;
        do {
            var page = await f.Engine.ListConversationsAsync(null, 20, default, cursor);
            found.AddRange(page.Items.Select(x => x.ConversationId)); cursor = page.NextConversationCursor;
            Assert.Equal(page.HasMore, cursor is not null);
        } while (cursor is not null);
        Assert.Equal(65, found.Count); Assert.Equal(65, found.Distinct().Count());
    }

    [Fact]
    public async Task ReadReceiptNeverRegressesWithStaleTrackedValue()
    {
        await using var f = await Fixture.CreateAsync();
        var c = (await f.Engine.CreateOrGetDirectAsync("staff:beta", default))!;
        for (var i = 0; i < 4; i++) await f.Engine.SendTextAsync(c.ConversationId, $"m{i}", "hello", default);
        Assert.Equal(1, await f.Engine.AdvanceReadReceiptAsync(c.ConversationId, 1, default));
        var stale = await f.Db.ChatReceipts.SingleAsync();
        Assert.Equal(4, await f.Engine.AdvanceReadReceiptAsync(c.ConversationId, 4, default));
        Assert.Equal(4, await f.Engine.AdvanceReadReceiptAsync(c.ConversationId, 2, default));
        Assert.Equal(4, (await f.Db.ChatReceipts.AsNoTracking().SingleAsync()).LastReadSequence);
    }

    [Fact]
    public async Task TextReplyIsScopedToTheSameConversationAndReturnedWithPreview()
    {
        await using var f = await Fixture.CreateAsync();
        var first = (await f.Engine.CreateOrGetDirectAsync("staff:beta", default))!;
        var second = (await f.Engine.CreateOrGetDirectAsync("staff:gamma", default))!;
        var root = (await f.Engine.SendTextAsync(first.ConversationId, "root", "original message", default)).Message!;
        var reply = (await f.Engine.SendTextAsync(first.ConversationId, "reply", "answer", root.MessageId, default)).Message!;

        Assert.Equal(root.MessageId, reply.ReplyToMessageId);
        Assert.Equal("staff:alpha", reply.ReplyToSenderSubjectId);
        Assert.Equal("text", reply.ReplyToKind);
        Assert.Equal("original message", reply.ReplyToText);
        Assert.Null(reply.ReplyToVoiceDurationMilliseconds);
        Assert.True((await f.Engine.SendTextAsync(second.ConversationId, "bad-reply", "denied", root.MessageId, default)).Conflict);
    }

    [Fact]
    public async Task ConcurrentInitialReceiptInsertsKeepTheHighestSequence()
    {
        await using var f = await Fixture.CreateAsync();
        var c = (await f.Engine.CreateOrGetDirectAsync("staff:beta", default))!;
        var conversation = await f.Db.ChatConversations.SingleAsync();
        for (var i = 0; i < 100; i++) conversation.AllocateSequence(f.Clock.Now);
        await f.Db.SaveChangesAsync();
        var barrier = new ReceiptInsertBarrier();
        var options = new DbContextOptionsBuilder<CommunicationDbContext>().UseSqlite(f.Connection)
            .ReplaceService<IModelCustomizer, SqliteChatModelCustomizer>().AddInterceptors(barrier).Options;
        await using var first = new CommunicationDbContext(options); await using var second = new CommunicationDbContext(options);
        var registry = new ChatPolicyRegistry([new Directory("app-one")], [new Policy("app-one")], new ApplicationReader());
        await Task.WhenAll(f.NewEngine(registry, first).AdvanceReadReceiptAsync(c.ConversationId, 100, default),
            f.NewEngine(registry, second).AdvanceReadReceiptAsync(c.ConversationId, 50, default));
        Assert.Equal(100, (await f.Db.ChatReceipts.AsNoTracking().SingleAsync()).LastReadSequence);
    }

    private sealed class ReceiptInsertBarrier : SaveChangesInterceptor
    {
        private int _arrivals;
        private readonly TaskCompletionSource _release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public override async ValueTask<InterceptionResult<int>> SavingChangesAsync(DbContextEventData data, InterceptionResult<int> result, CancellationToken token = default)
        {
            if (data.Context!.ChangeTracker.Entries<ChatReceipt>().Any(x => x.State == EntityState.Added))
            {
                if (Interlocked.Increment(ref _arrivals) == 2) _release.TrySetResult();
                await _release.Task.WaitAsync(TimeSpan.FromSeconds(5), token);
            }
            return result;
        }
    }

    [Fact]
    public async Task PostCommitFailurePreservesOriginalUploadAndRetryDoesNotDeleteIt()
    {
        var fault = new CommitFault(); await using var f = await Fixture.CreateAsync(fault);
        var c = (await f.Engine.CreateOrGetDirectAsync("staff:beta", default))!;
        fault.Fail = true;
        await Assert.ThrowsAsync<IOException>(() => f.Engine.SendVoiceAsync(c.ConversationId, "voice", new MemoryStream([1, 2, 3]), "audio/mp4", 1000, default));
        var transfer = await f.Db.ChatVoiceTransfers.AsNoTracking().SingleAsync();
        Assert.Contains(transfer.FileKey, f.Storage.Files.Keys);
        var retry = await f.Engine.SendVoiceAsync(c.ConversationId, "voice", new MemoryStream([1, 2, 3]), "audio/mp4", 1000, default);
        Assert.Equal(transfer.Id, retry.Message!.VoiceTransferId); Assert.Single(f.Storage.Files);
        f.Actor.Current = f.Actor.Current! with { SubjectId = "staff:beta" };
        var downloaded = await f.Engine.DownloadVoiceAsync(transfer.Id, default);
        Assert.NotNull(downloaded); await downloaded.Value.Content.DisposeAsync();
    }

    [Fact]
    public async Task VoiceAckAndExpiryPersistTerminalIntentAndRetryPhysicalDeletion()
    {
        await using var f = await Fixture.CreateAsync();
        var c = (await f.Engine.CreateOrGetDirectAsync("staff:beta", default))!;
        var sent = (await f.Engine.SendVoiceAsync(c.ConversationId, "voice", new MemoryStream([1, 2, 3]), "audio/mp4", 1000, default)).Message!;
        var transfer = await f.Db.ChatVoiceTransfers.SingleAsync();
        Assert.Equal(TimeSpan.FromDays(7), transfer.ExpiresAtUtc - transfer.PublishedAtUtc); // configured 30 clamps to 7
        Assert.False(await f.Engine.AcknowledgeVoiceAsync(transfer.Id, f.Recipients.Device, transfer.Sha256, transfer.Length, default));
        f.Actor.Current = f.Actor.Current! with { SubjectId = "staff:beta", Mechanism = ChatActorMechanism.PairedDevice, InstallationId = f.Recipients.Device };
        Assert.False(await f.Engine.AcknowledgeVoiceAsync(transfer.Id, Guid.NewGuid(), transfer.Sha256, transfer.Length, default));
        Assert.False(await f.Engine.AcknowledgeVoiceAsync(transfer.Id, f.Recipients.Device, new string('f', 64), transfer.Length, default));
        f.Storage.FailDelete = true;
        Assert.True(await f.Engine.AcknowledgeVoiceAsync(transfer.Id, f.Recipients.Device, transfer.Sha256, transfer.Length, default));
        Assert.Null(await f.Engine.DownloadVoiceAsync(transfer.Id, default));
        Assert.Equal(ChatVoiceTransferState.AcknowledgedDeletionPending, transfer.State);
        Assert.True(await f.Engine.AcknowledgeVoiceAsync(transfer.Id, f.Recipients.Device, transfer.Sha256, transfer.Length, default));
        f.Storage.FailDelete = false;
        await new ChatVoiceSweeper(f.Db, f.Storage, f.Clock).SweepAsync(default);
        Assert.Empty(f.Storage.Files);
        Assert.Equal(ChatVoiceTransferState.AcknowledgedDeleted, transfer.State);

        f.Actor.Current = f.Actor.Current with { SubjectId = "staff:alpha", Mechanism = ChatActorMechanism.ApplicationSession, InstallationId = null };
        await f.Engine.SendVoiceAsync(c.ConversationId, "expiring", new MemoryStream([4, 5]), "audio/mp4", 1000, default);
        f.Storage.Files["orphan.partial"] = [0]; f.Clock.Now += TimeSpan.FromDays(8);
        f.Storage.FailDelete = true; await new ChatVoiceSweeper(f.Db, f.Storage, f.Clock).SweepAsync(default);
        Assert.Contains(await f.Db.ChatVoiceTransfers.ToListAsync(), x => x.State == ChatVoiceTransferState.ExpiredDeletionPending);
        f.Storage.FailDelete = false; await new ChatVoiceSweeper(f.Db, f.Storage, f.Clock).SweepAsync(default);
        Assert.Empty(f.Storage.Files);
    }

    [Fact]
    public async Task TwoApplicationAdaptersSupportOpaqueSubjectsAndDenyForeignConversation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.Engine.CreateOrGetDirectAsync("staff:beta", default);
        Assert.NotNull(first);
        fixture.Actor.Current = new ChatActor(new ChatApplication(Guid.NewGuid(), "app-two"), "staff:alpha", ChatActorMechanism.PairedDevice, Guid.NewGuid());
        var foreign = await fixture.Engine.SendTextAsync(first.ConversationId, "foreign-key", "denied", default);
        Assert.True(foreign.Forbidden);
        var own = await fixture.Engine.CreateOrGetDirectAsync("staff:beta", default);
        Assert.NotNull(own);
        Assert.NotEqual(first.ConversationId, own.ConversationId);
        Assert.NotNull((await fixture.Engine.SendTextAsync(own.ConversationId, "own-key", "allowed", default)).Message);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public required SqliteConnection Connection { get; init; }
        public required CommunicationDbContext Db { get; init; }
        public required ActorResolver Actor { get; init; }
        public required ChatEngine Engine { get; init; }
        public required VoiceStorage Storage { get; init; }
        public required Recipients Recipients { get; init; }
        public required FakeClock Clock { get; init; }
        public ChatEngine NewEngine(ChatPolicyRegistry registry, CommunicationDbContext? context = null) => new(context ?? Db, new TestHttpAccessor { HttpContext = new DefaultHttpContext() }, Actor, registry, Storage, Recipients, Options.Create(new ChatVoiceOptions { PublishedRetentionDays = 30 }), Clock);
        public static async Task<Fixture> CreateAsync(CommitFault? fault = null)
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            connection.CreateCollation("Latin1_General_100_BIN2", StringComparer.Ordinal.Compare);
            var options = new DbContextOptionsBuilder<CommunicationDbContext>().UseSqlite(connection).ReplaceService<IModelCustomizer, SqliteChatModelCustomizer>();
            if (fault is not null) options.AddInterceptors(fault);
            var db = new CommunicationDbContext(options.Options);
            await db.Database.EnsureCreatedAsync();
            var actor = new ActorResolver { Current = new ChatActor(new ChatApplication(Guid.NewGuid(), "app-one"), "staff:alpha", ChatActorMechanism.ApplicationSession) };
            var registry = new ChatPolicyRegistry([new Directory("app-one"), new Directory("app-two")], [new Policy("app-one"), new Policy("app-two")], new ApplicationReader());
            var storage = new VoiceStorage(); var recipients = new Recipients(); var clock = new FakeClock();
            var engine = new ChatEngine(db, new TestHttpAccessor { HttpContext = new DefaultHttpContext() }, actor, registry, storage, recipients, Options.Create(new ChatVoiceOptions { PublishedRetentionDays = 30 }), clock);
            return new Fixture { Connection = connection, Db = db, Actor = actor, Engine = engine, Storage = storage, Recipients = recipients, Clock = clock };
        }
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await Connection.DisposeAsync(); }
    }
    private sealed class ActorResolver : IChatActorResolver
    {
        public ChatActor? Current { get; set; }
        public Task<ChatActor?> ResolveAsync(ClaimsPrincipal principal, CancellationToken token) => Task.FromResult(Current);
    }
    private sealed class TestHttpAccessor : IHttpContextAccessor { public HttpContext? HttpContext { get; set; } }
    private sealed class Directory(string key) : IChatParticipantDirectory
    {
        public string ApplicationKey => key;
        public Task<ChatParticipant?> FindByReferenceAsync(ChatApplication application, string value, CancellationToken token) => Task.FromResult<ChatParticipant?>(new(value, value, value));
        public Task<ChatParticipant?> FindBySubjectAsync(ChatApplication application, string value, CancellationToken token) => FindByReferenceAsync(application, value, token);
    }
    private sealed class Policy(string key) : IChatAccessPolicy
    {
        public string ApplicationKey => key;
        public Task<bool> CanStartDirectConversationAsync(ChatActor actor, ChatParticipant self, ChatParticipant counterpart, CancellationToken token) => Task.FromResult(true);
        public Task<bool> IsBidirectionallyBlockedAsync(ChatApplication application, ChatParticipant first, ChatParticipant second, CancellationToken token) => Task.FromResult(false);
    }
    private sealed class ApplicationReader : IPlatformClientDescriptorReader
    {
        public Task<PlatformClientDescriptor?> FindAsync(Guid id, CancellationToken token) => Task.FromResult<PlatformClientDescriptor?>(null);
    }
    private sealed class Recipients : IMobileRecipientResolver
    {
        public Guid Device { get; } = Guid.NewGuid();
        public Task<IReadOnlyList<MobileRecipientDevice>> ResolveActiveDevicesAsync(NotificationApplicationContext application, string subject, CancellationToken token) => Task.FromResult<IReadOnlyList<MobileRecipientDevice>>([new(Device, "synthetic-installation", "android", null)]);
    }
    private sealed class VoiceStorage : IChatVoiceStorage
    {
        public Dictionary<string, byte[]> Files { get; } = [];
        public bool FailDelete { get; set; }
        public async Task<ChatVoiceUpload> PublishAsync(Stream source, string type, CancellationToken token) {
            using var output = new MemoryStream(); await source.CopyToAsync(output, token); var bytes = output.ToArray();
            var key = Guid.NewGuid().ToString("N") + ".m4a"; Files[key] = bytes;
            return new(key, Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(), bytes.Length, 1000);
        }
        public Stream OpenRead(string key) => new MemoryStream(Files[key]);
        public bool Delete(string key) { if (FailDelete) return false; Files.Remove(key); return true; }
        public IReadOnlyCollection<string> EnumerateFileKeys() => Files.Keys.ToArray();
        public DateTimeOffset? LastWriteTimeUtc(string key) => DateTimeOffset.Parse("2026-10-07T00:00:00Z");
    }
    private sealed class FakeClock : TimeProvider { public DateTimeOffset Now { get; set; } = DateTimeOffset.Parse("2026-10-07T00:00:00Z"); public override DateTimeOffset GetUtcNow() => Now; }
    private sealed class CommitFault : DbTransactionInterceptor {
        public bool Fail { get; set; }
        public override Task TransactionCommittedAsync(DbTransaction transaction, TransactionEndEventData data, CancellationToken token = default) {
            if (Fail) { Fail = false; throw new IOException("synthetic post-commit failure"); } return Task.CompletedTask;
        }
    }
}

public sealed class SqliteChatModelCustomizer(ModelCustomizerDependencies dependencies) : ModelCustomizer(dependencies)
{
    public override void Customize(ModelBuilder builder, DbContext context)
    {
        base.Customize(builder, context);
        foreach (var entity in builder.Model.GetEntityTypes()) foreach (var p in entity.GetProperties())
            if (p.ClrType == typeof(DateTimeOffset) || p.ClrType == typeof(DateTimeOffset?)) p.SetValueConverter(new DateTimeOffsetToBinaryConverter());
    }
}
