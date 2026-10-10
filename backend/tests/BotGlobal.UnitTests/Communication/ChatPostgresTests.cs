using System.Security.Claims;
using System.Text.Json;
using System.Text.RegularExpressions;
using BotGlobal.Communication.Application.Chat;
using BotGlobal.Communication.Domain.Chat;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Communication.Infrastructure.Persistence.Migrations;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.EntityFrameworkCore.Migrations;
using Microsoft.EntityFrameworkCore.Migrations.Operations;
using Microsoft.Extensions.Options;
using Npgsql;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatPostgresTests
{
    private const string Provider = "Npgsql.EntityFrameworkCore.PostgreSQL";
    private static readonly string[] Tables =
        ["ChatConversations", "ChatDispatches", "ChatMessages", "ChatReceipts", "ChatVoiceTransfers"];

    [Fact]
    public void Actual_npgsql_model_and_chat_migration_have_identical_native_schema()
    {
        using var db = new CommunicationDesignTimeDbContextFactory().CreateDbContext([]);
        Assert.Equal(Provider, db.Database.ProviderName);
        var model = db.GetService<IDesignTimeModel>().Model;
        var operations = ChatOperations(db, model);
        var migration = new AddApplicationScopedChat { ActiveProvider = Provider };
        Assert.Equal(SchemaSignature(operations), SchemaSignature(migration.UpOperations));
        Assert.Equal(Tables, operations.OfType<CreateTableOperation>().Select(x => x.Name).Order().ToArray());
        Assert.Throws<NotSupportedException>(() => migration.DownOperations);

        var sql = string.Join("\n", db.GetService<IMigrationsSqlGenerator>()
            .Generate(migration.UpOperations, model).Select(x => x.CommandText));
        Assert.Contains("uuid", sql);
        Assert.Contains("timestamp with time zone", sql);
        Assert.Contains("COLLATE \"C\"", sql);
        foreach (var forbidden in new[] { "uniqueidentifier", "nvarchar", "datetimeoffset", "Latin1_", "[MessageId]", "CallSessions" })
            Assert.DoesNotContain(forbidden, sql);

        var snapshot = db.GetService<IMigrationsAssembly>().ModelSnapshot!.Model;
        foreach (var entity in model.GetEntityTypes().Where(IsChat))
        {
            foreach (var property in entity.GetProperties())
            {
                var saved = snapshot.FindEntityType(entity.Name)!.FindProperty(property.Name)!;
                var target = migration.TargetModel.FindEntityType(entity.Name)!.FindProperty(property.Name)!;
                Assert.Equal(property.GetColumnType(), saved.GetColumnType());
                Assert.Equal(property.GetColumnType(), target.GetColumnType());
                Assert.Equal(property.GetCollation(), saved.GetCollation());
                Assert.Equal(property.GetCollation(), target.GetCollation());
                Assert.Equal(property.IsConcurrencyToken, saved.IsConcurrencyToken);
            }
        }
        Assert.True(model.FindEntityType(typeof(ChatConversation))!.FindProperty("Version")!.IsConcurrencyToken);
        Assert.True(model.FindEntityType(typeof(ChatDispatch))!.FindProperty("LeaseId")!.IsConcurrencyToken);
    }

    [Theory]
    [InlineData("Host=prod;Database=chat_rehearsal_safe;Username=test;Password=synthetic")]
    [InlineData("Host=127.0.0.1;Database=botglobal_communication;Username=test;Password=synthetic")]
    [InlineData("Host=localhost,prod;Database=chat_rehearsal_safe;Username=test;Password=synthetic")]
    [InlineData("Host=/tmp;Database=chat_rehearsal_safe;Username=test;Password=synthetic")]
    [InlineData("Host=localhost;Database=chat_rehearsal_;Username=test;Password=synthetic")]
    [InlineData("Host=localhost;Database=chat_rehearsal_safe;Username=test")]
    [InlineData("Host=localhost;Database=chat_rehearsal_safe;Username=test;Password=synthetic;Options=-c search_path=foreign")]
    public void Integration_guard_rejects_non_dedicated_or_implicit_targets(string value) =>
        Assert.Throws<InvalidOperationException>(() => GuardConnection(value));

    [PostgresFact]
    public async Task Standalone_artifact_rehearsal_guards_schema_and_actual_chat_persistence()
    {
        // Parent provisions ONE fresh dedicated database. No containers, database creation,
        // providers, EnsureCreated or migrations are started here. Leave evidence for parent teardown.
        var connectionString = GuardConnection(Environment.GetEnvironmentVariable("BOTGLOBAL_CHAT_TEST_POSTGRES")!);
        await using var connection = new NpgsqlConnection(connectionString);
        await connection.OpenAsync();
        Assert.Equal(0L, await Scalar<long>(connection,
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','v','m','f','S')"));
        var artifact = File.ReadAllText(FindArtifact());
        var database = new NpgsqlConnectionStringBuilder(connectionString).Database!;
        await Execute(connection, "SELECT set_config('botglobal.chat_activation_database', @database, false)", database);
        await Execute(connection, "SET botglobal.chat_activation_version = 'wrong'");
        await RejectArtifact(connection, artifact, "chat_activation_target_or_version_not_approved");
        Assert.False(await Scalar<bool>(connection, "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='communication')"));
        await Execute(connection, "SET botglobal.chat_activation_version = '001'");

        // Fixtures and failed application share a transaction. ROLLBACK must preserve the
        // original empty database, with no table repair/drop required by this test.
        await Execute(connection, "BEGIN; CREATE SCHEMA communication; CREATE TABLE communication.\"ChatMessages\" (wrong integer)");
        await RejectArtifact(connection, artifact, "chat_activation_existing_objects_rejected");
        Assert.False(await Scalar<bool>(connection, "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='communication')"));

        await Execute(connection, "BEGIN; CREATE SCHEMA communication; CREATE TABLE communication.\"__EFMigrationsHistory\" (\"MigrationId\" text); INSERT INTO communication.\"__EFMigrationsHistory\" VALUES ('20261007153116_AddApplicationScopedChat')");
        await RejectArtifact(connection, artifact, "chat_activation_existing_ef_history_rejected");
        Assert.False(await Scalar<bool>(connection, "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='communication')"));

        // Late DDL failure: conflicting index name after some tables were created must
        // also roll back the whole activation, rather than leaving a partial schema.
        await Execute(connection, "BEGIN; CREATE SCHEMA communication; CREATE TABLE communication.\"IX_ChatVoiceTransfers_ApplicationId_FileKey\" (wrong integer)");
        var collision = await Assert.ThrowsAsync<PostgresException>(() => Execute(connection, artifact));
        Assert.Equal(PostgresErrorCodes.DuplicateTable, collision.SqlState);
        await Execute(connection, "ROLLBACK");
        Assert.False(await Scalar<bool>(connection, "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='communication')"));

        await Execute(connection, artifact);
        Assert.Equal(5L, await Scalar<long>(connection, "SELECT count(*) FROM pg_tables WHERE schemaname='communication'"));
        Assert.Equal("botglobal-chat:001:20261007153116_AddApplicationScopedChat:standalone-postgresql",
            await Scalar<string>(connection, "SELECT obj_description('communication.\"ChatConversations\"'::regclass)"));
        Assert.False(await Scalar<bool>(connection, "SELECT to_regclass('communication.\"__EFMigrationsHistory\"') IS NOT NULL"));
        await RejectArtifact(connection, artifact, "chat_activation_existing_objects_rejected");
        Assert.Equal(5L, await Scalar<long>(connection, "SELECT count(*) FROM pg_tables WHERE schemaname='communication'"));

        await using var db = Context(connectionString);
        await AssertCatalogMatchesModel(connection, db);
        await ExerciseChat(connectionString, db);
        var messageCount = await db.ChatMessages.CountAsync();
        await RejectArtifact(connection, artifact, "chat_activation_existing_objects_rejected");
        Assert.Equal(messageCount, await db.ChatMessages.CountAsync());
    }

    private static async Task ExerciseChat(string connectionString, CommunicationDbContext db)
    {
        var app = Guid.NewGuid();
        var actor = new Actor(new ChatActor(new ChatApplication(app, "rehearsal"), "عضو:Alpha", ChatActorMechanism.ApplicationSession));
        var engine = Engine(db, actor);
        var conversation = (await engine.CreateOrGetDirectAsync("عضو:Beta", default))!;
        Assert.NotNull(conversation);
        Assert.Equal(conversation.ConversationId, (await engine.CreateOrGetDirectAsync("عضو:Beta", default))!.ConversationId);
        var first = (await engine.SendTextAsync(conversation.ConversationId, "Client:أA", "رسالة", default)).Message!;
        var retry = (await engine.SendTextAsync(conversation.ConversationId, "Client:أA", "رسالة", default)).Message!;
        Assert.Equal(first.MessageId, retry.MessageId);
        Assert.True((await engine.SendTextAsync(conversation.ConversationId, "Client:أA", "different", default)).Conflict);
        var second = (await engine.SendTextAsync(conversation.ConversationId, "Client:أa", "رسالة ثانية", default)).Message!;
        Assert.Equal(first.Sequence + 1, second.Sequence);
        Assert.Equal(2, await db.ChatMessages.CountAsync());
        Assert.Equal(2, await db.ChatDispatches.CountAsync());

        var lower = new ChatConversation(app, "عضو:alpha", "عضو:Beta", DateTimeOffset.UtcNow);
        var composed = new ChatConversation(app, "café", "peer", DateTimeOffset.UtcNow);
        var decomposed = new ChatConversation(app, "cafe\u0301", "peer", DateTimeOffset.UtcNow);
        db.ChatConversations.AddRange(lower, composed, decomposed);
        await db.SaveChangesAsync();
        Assert.NotEqual(conversation.ConversationId, lower.Id);
        Assert.Equal(1, await db.ChatConversations.CountAsync(x => x.ApplicationId == app && x.DirectPairKey == composed.DirectPairKey));

        var otherApp = Guid.NewGuid();
        actor.Current = actor.Current with { Application = new ChatApplication(otherApp, "rehearsal") };
        Assert.True((await engine.SendTextAsync(conversation.ConversationId, "foreign", "denied", default)).Forbidden);
        var other = (await engine.CreateOrGetDirectAsync("عضو:Beta", default))!;
        Assert.NotEqual(conversation.ConversationId, other.ConversationId);
        Assert.NotNull((await engine.SendTextAsync(other.ConversationId, "Client:أA", "رسالة", default)).Message);
        actor.Current = actor.Current with { Application = new ChatApplication(app, "rehearsal") };

        // Assert actual PostgreSQL errors, including cross-app composite relationships.
        await ConstraintError(connectionString, x => x.ChatMessages.Add(new ChatMessage(app, conversation.ConversationId,
            99, "عضو:Alpha", "Client:أA", ChatMessageKind.Text, new string('a', 64), DateTimeOffset.UtcNow, "duplicate")), PostgresErrorCodes.UniqueViolation);
        await ConstraintError(connectionString, x => x.ChatMessages.Add(new ChatMessage(app, conversation.ConversationId,
            first.Sequence, "عضو:Alpha", "new-key-same-sequence", ChatMessageKind.Text, new string('a', 64), DateTimeOffset.UtcNow, "duplicate sequence")), PostgresErrorCodes.UniqueViolation);
        await ConstraintError(connectionString, x => x.ChatMessages.Add(new ChatMessage(otherApp, conversation.ConversationId,
            99, "subject", "wrong-app", ChatMessageKind.Text, new string('a', 64), DateTimeOffset.UtcNow, "bad fk")), PostgresErrorCodes.ForeignKeyViolation);
        await ConstraintError(connectionString, x => x.ChatDispatches.Add(new ChatDispatch(otherApp, first.MessageId, "peer", DateTimeOffset.UtcNow)), PostgresErrorCodes.ForeignKeyViolation);
        await ConstraintError(connectionString, x => x.ChatReceipts.Add(new ChatReceipt(otherApp, conversation.ConversationId, "peer", 1, DateTimeOffset.UtcNow)), PostgresErrorCodes.ForeignKeyViolation);

        Assert.Equal(1, await engine.AdvanceReadReceiptAsync(conversation.ConversationId, 1, default));
        _ = await db.ChatReceipts.SingleAsync(x => x.ConversationId == conversation.ConversationId);
        await using (var fresh = Context(connectionString))
            Assert.Equal(2, await Engine(fresh, actor).AdvanceReadReceiptAsync(conversation.ConversationId, 2, default));
        Assert.Equal(2, await engine.AdvanceReadReceiptAsync(conversation.ConversationId, 1, default));
        Assert.Equal(2, (await db.ChatReceipts.AsNoTracking().SingleAsync(x => x.ConversationId == conversation.ConversationId)).LastReadSequence);

        await using var writer1 = Context(connectionString);
        await using var writer2 = Context(connectionString);
        var one = await writer1.ChatConversations.SingleAsync(x => x.Id == conversation.ConversationId);
        var stale = await writer2.ChatConversations.SingleAsync(x => x.Id == conversation.ConversationId);
        one.AllocateSequence(DateTimeOffset.UtcNow);
        stale.AllocateSequence(DateTimeOffset.UtcNow);
        await writer1.SaveChangesAsync();
        await Assert.ThrowsAsync<DbUpdateConcurrencyException>(() => writer2.SaveChangesAsync());

        // Lease compare-and-swap and EF concurrency use actual PostgreSQL affected rows.
        var lease = Guid.NewGuid();
        await using var leased = Context(connectionString);
        var intent = await leased.ChatDispatches.SingleAsync(x => x.MessageId == first.MessageId);
        Assert.Equal(1, await db.ChatDispatches.Where(x => x.Id == intent.Id && x.LeaseId == null)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.LeaseId, lease)));
        Assert.Equal(0, await db.ChatDispatches.Where(x => x.Id == intent.Id && x.LeaseId == null)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.LeaseId, Guid.NewGuid())));
        intent.Delivered();
        await Assert.ThrowsAsync<DbUpdateConcurrencyException>(() => leased.SaveChangesAsync());

        var now = DateTimeOffset.UtcNow;
        var voice = new ChatVoiceTransfer(Guid.NewGuid(), app, conversation.ConversationId, "عضو:Alpha", "عضو:Beta",
            "ملف:A.m4a", new string('a', 64), 100, 1000, now, now.AddDays(7));
        var caseVoice = new ChatVoiceTransfer(Guid.NewGuid(), app, conversation.ConversationId, "عضو:Alpha", "عضو:Beta",
            "ملف:a.m4a", new string('a', 64), 100, 1000, now, now.AddDays(7));
        db.ChatVoiceTransfers.AddRange(voice, caseVoice);
        await db.SaveChangesAsync(); // multiple nullable MessageId values remain legal.
        await ConstraintError(connectionString, x => x.ChatVoiceTransfers.Add(new ChatVoiceTransfer(Guid.NewGuid(),
            otherApp, conversation.ConversationId, "sender", "peer", "foreign.m4a", new string('a', 64), 100, 1000, now, now.AddDays(7))), PostgresErrorCodes.ForeignKeyViolation);
        await ConstraintError(connectionString, x =>
        {
            var foreignMessage = new ChatVoiceTransfer(Guid.NewGuid(), otherApp, other.ConversationId,
                "sender", "peer", "foreign-message.m4a", new string('a', 64), 100, 1000, now, now.AddDays(7));
            foreignMessage.AttachMessage(first.MessageId);
            x.ChatVoiceTransfers.Add(foreignMessage);
        }, PostgresErrorCodes.ForeignKeyViolation);
        Assert.True(voice.Acknowledge("عضو:Beta", null, voice.Sha256, voice.Length, now));
        await db.SaveChangesAsync();
        await using var reloaded = Context(connectionString);
        var savedVoice = await reloaded.ChatVoiceTransfers.SingleAsync(x => x.FileKey == "ملف:A.m4a");
        Assert.Equal(ChatVoiceTransferState.AcknowledgedDeletionPending, savedVoice.State);
        Assert.False(savedVoice.IsDownloadable(now));
        Assert.True(savedVoice.Acknowledge("عضو:Beta", null, voice.Sha256, voice.Length, now));
        Assert.Equal(2, await reloaded.ChatVoiceTransfers.CountAsync());
    }

    private static async Task ConstraintError(string connectionString, Action<CommunicationDbContext> add, string expected)
    {
        await using var db = Context(connectionString);
        add(db);
        var error = await Assert.ThrowsAsync<DbUpdateException>(() => db.SaveChangesAsync());
        Assert.Equal(expected, Assert.IsType<PostgresException>(error.InnerException).SqlState);
    }

    private static bool IsChat(IReadOnlyEntityType entity) => Tables.Contains(entity.GetTableName());
    private static CommunicationDbContext Context(string value) => new(new DbContextOptionsBuilder<CommunicationDbContext>().UseNpgsql(value).Options);

    private static IReadOnlyList<MigrationOperation> ChatOperations(CommunicationDbContext db, IModel model) =>
        db.GetService<IMigrationsModelDiffer>().GetDifferences(null, model.GetRelationalModel())
            .Where(x => x is CreateTableOperation table && Tables.Contains(table.Name)
                || x is CreateIndexOperation index && Tables.Contains(index.Table)).ToArray();

    private static string SchemaSignature(IEnumerable<MigrationOperation> operations) => JsonSerializer.Serialize(
        operations.Where(x => x is CreateTableOperation or CreateIndexOperation).Select(x => x switch
        {
            CreateTableOperation table => "table:" + table.Name + ":" + table.Schema + ":" + JsonSerializer.Serialize(new
            {
                Columns = table.Columns.OrderBy(c => c.Name).Select(c => new { c.Name, c.ColumnType, c.IsNullable, c.Collation, c.DefaultValueSql }),
                Primary = table.PrimaryKey!.Name + ":" + string.Join(",", table.PrimaryKey.Columns),
                Unique = table.UniqueConstraints.Select(c => c.Name + ":" + string.Join(",", c.Columns)).Order(),
                Foreign = table.ForeignKeys.Select(c => c.Name + ":" + string.Join(",", c.Columns) + ":" + c.PrincipalSchema + ":" + c.PrincipalTable + ":" + string.Join(",", c.PrincipalColumns!) + ":" + c.OnDelete).Order()
            }),
            CreateIndexOperation index => "index:" + index.Name + ":" + index.Schema + ":" + index.Table + ":" + string.Join(",", index.Columns) + ":" + index.IsUnique + ":" + index.Filter,
            _ => throw new InvalidOperationException()
        }).Order());

    private static async Task AssertCatalogMatchesModel(NpgsqlConnection connection, CommunicationDbContext db)
    {
        var model = db.GetService<IDesignTimeModel>().Model;
        foreach (var entity in model.GetEntityTypes().Where(IsChat))
        {
            var table = entity.GetTableName()!;
            await using var command = new NpgsqlCommand("SELECT a.attname, format_type(a.atttypid,a.atttypmod), a.attnotnull, co.collname FROM pg_attribute a JOIN pg_class t ON t.oid=a.attrelid JOIN pg_namespace n ON n.oid=t.relnamespace LEFT JOIN pg_collation co ON co.oid=a.attcollation WHERE n.nspname='communication' AND t.relname=@table AND a.attnum>0 AND NOT a.attisdropped", connection);
            command.Parameters.AddWithValue("table", table);
            await using var reader = await command.ExecuteReaderAsync();
            var found = 0;
            while (await reader.ReadAsync())
            {
                var property = entity.FindProperty(reader.GetString(0));
                Assert.NotNull(property);
                Assert.Equal(property.GetColumnType(), reader.GetString(1));
                Assert.Equal(!property.IsNullable, reader.GetBoolean(2));
                if (property.GetCollation() is { } collation) Assert.Equal(collation, reader.GetString(3));
                found++;
            }
            Assert.Equal(entity.GetProperties().Count(), found);
        }

        // Independently create the actual Npgsql Chat model's DDL in a transaction-only
        // reference schema, compare catalog definitions, then roll it all back.
        var operations = ChatOperations(db, model);
        var generated = string.Join("\n", db.GetService<IMigrationsSqlGenerator>().Generate(operations, model).Select(x => x.CommandText));
        await Execute(connection, "BEGIN; CREATE SCHEMA chat_model_reference");
        try
        {
            await Execute(connection, generated.Replace("communication.", "chat_model_reference.", StringComparison.Ordinal));
            const string catalog = "SELECT 'constraint:' || t.relname || ':' || c.conname || ':' || pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=@schema UNION ALL SELECT 'index:' || tablename || ':' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname=@schema ORDER BY 1";
            var actual = await Catalog(connection, catalog, "communication");
            var expected = await Catalog(connection, catalog, "chat_model_reference");
            Assert.Equal(expected, actual);
        }
        finally { await Execute(connection, "ROLLBACK"); }
    }

    private static async Task<string[]> Catalog(NpgsqlConnection connection, string sql, string schema)
    {
        await using var command = new NpgsqlCommand(sql, connection);
        command.Parameters.AddWithValue("schema", schema);
        await using var reader = await command.ExecuteReaderAsync();
        var result = new List<string>();
        while (await reader.ReadAsync()) result.Add(reader.GetString(0).Replace(schema + ".", "<schema>.", StringComparison.Ordinal));
        return result.Order().ToArray();
    }

    private static string GuardConnection(string value)
    {
        NpgsqlConnectionStringBuilder builder;
        try { builder = new NpgsqlConnectionStringBuilder(value); }
        catch { throw new InvalidOperationException("Invalid dedicated PostgreSQL test configuration."); }
        string[] allowed = ["Host", "Port", "Database", "Username", "Password", "SSL Mode", "Include Error Detail"];
        if (builder.Keys.Cast<string>().Any(x => !allowed.Contains(x, StringComparer.OrdinalIgnoreCase))
            || builder.Host is not ("localhost" or "127.0.0.1")
            || !Regex.IsMatch(builder.Database ?? "", "^chat_rehearsal_[a-z0-9_]+$")
            || string.IsNullOrWhiteSpace(builder.Username) || string.IsNullOrWhiteSpace(builder.Password))
            throw new InvalidOperationException("PostgreSQL tests require explicit generated credentials, localhost/127.0.0.1 and a fresh chat_rehearsal_ database; additional connection options are prohibited.");
        builder.IncludeErrorDetail = false;
        builder.SslMode = SslMode.Disable; // dedicated loopback rehearsal; no certificate discovery.
        builder.Pooling = false;
        builder.Timeout = 5;
        builder.CommandTimeout = 30;
        return builder.ConnectionString;
    }

    private static string FindArtifact()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var path = Path.Combine(directory.FullName, "infra", "apps-prod-01", "postgres-chat", "001-application-scoped-chat.sql");
            if (File.Exists(path)) return path;
        }
        throw new FileNotFoundException("Run from the repository build output so the actual approved SQL artifact can be located.");
    }

    private static async Task RejectArtifact(NpgsqlConnection connection, string artifact, string expected)
    {
        var error = await Assert.ThrowsAsync<PostgresException>(() => Execute(connection, artifact));
        Assert.Equal(PostgresErrorCodes.RaiseException, error.SqlState);
        Assert.Equal(expected, error.MessageText);
        await Execute(connection, "ROLLBACK");
    }

    private static async Task Execute(NpgsqlConnection connection, string sql, string? database = null)
    {
        await using var command = new NpgsqlCommand(sql, connection);
        if (database is not null) command.Parameters.AddWithValue("database", database);
        await command.ExecuteNonQueryAsync();
    }

    private static async Task<T> Scalar<T>(NpgsqlConnection connection, string sql)
    {
        await using var command = new NpgsqlCommand(sql, connection);
        return (T)(await command.ExecuteScalarAsync())!;
    }

    private static ChatEngine Engine(CommunicationDbContext db, Actor actor) => new(db,
        new HttpContextAccessor { HttpContext = new DefaultHttpContext() }, actor,
        new ChatPolicyRegistry([new DirectoryAdapter()], [new Policy()], new ApplicationReader()),
        new NoVoiceStorage(), new NoRecipients(), Options.Create(new ChatVoiceOptions()), TimeProvider.System);

    private sealed class Actor(ChatActor value) : IChatActorResolver
    {
        public ChatActor Current { get; set; } = value;
        public Task<ChatActor?> ResolveAsync(ClaimsPrincipal principal, CancellationToken token) => Task.FromResult<ChatActor?>(Current);
    }
    private sealed class DirectoryAdapter : IChatParticipantDirectory
    {
        public string ApplicationKey => "rehearsal";
        public Task<ChatParticipant?> FindByReferenceAsync(ChatApplication app, string subject, CancellationToken token) => Task.FromResult<ChatParticipant?>(new(subject, subject, subject));
        public Task<ChatParticipant?> FindBySubjectAsync(ChatApplication app, string subject, CancellationToken token) => FindByReferenceAsync(app, subject, token);
    }
    private sealed class Policy : IChatAccessPolicy
    {
        public string ApplicationKey => "rehearsal";
        public Task<bool> CanStartDirectConversationAsync(ChatActor actor, ChatParticipant self, ChatParticipant other, CancellationToken token) => Task.FromResult(true);
        public Task<bool> IsBidirectionallyBlockedAsync(ChatApplication app, ChatParticipant self, ChatParticipant other, CancellationToken token) => Task.FromResult(false);
    }
    private sealed class ApplicationReader : IPlatformClientDescriptorReader
    {
        public Task<PlatformClientDescriptor?> FindAsync(Guid id, CancellationToken token) => Task.FromResult<PlatformClientDescriptor?>(null);
    }
    private sealed class NoRecipients : IMobileRecipientResolver
    {
        public Task<IReadOnlyList<MobileRecipientDevice>> ResolveActiveDevicesAsync(NotificationApplicationContext app, string subject, CancellationToken token) => Task.FromResult<IReadOnlyList<MobileRecipientDevice>>([]);
    }
    private sealed class NoVoiceStorage : IChatVoiceStorage
    {
        public Task<ChatVoiceUpload> PublishAsync(Stream source, string contentType, CancellationToken token) => throw new NotSupportedException();
        public Stream OpenRead(string key) => throw new NotSupportedException();
        public bool Delete(string key) => throw new NotSupportedException();
        public IReadOnlyCollection<string> EnumerateFileKeys() => throw new NotSupportedException();
        public DateTimeOffset? LastWriteTimeUtc(string key) => throw new NotSupportedException();
    }
}

public sealed class PostgresFactAttribute : FactAttribute
{
    public PostgresFactAttribute()
    {
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("BOTGLOBAL_CHAT_TEST_POSTGRES")))
            Skip = "Parent must provision a fresh local chat_rehearsal_ PostgreSQL database and set BOTGLOBAL_CHAT_TEST_POSTGRES; no database/provider is started by tests.";
    }
}
