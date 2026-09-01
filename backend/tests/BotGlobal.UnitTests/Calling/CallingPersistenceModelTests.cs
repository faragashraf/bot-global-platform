using BotGlobal.Calling;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallingPersistenceModelTests : IDisposable
{
    private readonly CallingDbContext context = new(
        new DbContextOptionsBuilder<CallingDbContext>()
            .UseInMemoryDatabase($"calling-model-{Guid.NewGuid():N}")
            .Options);

    [Fact]
    public void Model_uses_isolated_calling_schema_and_minimal_activity_tables()
    {
        var tables = context.Model.GetEntityTypes()
            .Select(entity => (entity.GetSchema(), entity.GetTableName()))
            .OrderBy(table => table.Item2, StringComparer.Ordinal)
            .ToArray();

        Assert.Equal(4, tables.Length);
        Assert.All(tables, table => Assert.Equal(CallingModule.DatabaseSchema, table.Item1));
        Assert.Equal(["CallParticipants", "CallUsageReports", "Calls", "UsageCounterPeriods"],
            tables.Select(table => table.Item2!).ToArray());
    }

    [Fact]
    public void Participant_usage_and_open_period_uniqueness_prevent_double_counting()
    {
        var participants = context.Model.FindEntityType(typeof(CallParticipantRecord))!;
        var usage = context.Model.FindEntityType(typeof(CallUsageReport))!;
        var periods = context.Model.FindEntityType(typeof(UsageCounterPeriod))!;

        Assert.Contains(participants.GetKeys(), key =>
            key.Properties.Select(property => property.Name).SequenceEqual(["CallId", "MembershipId"]));
        Assert.True(usage.GetIndexes().Single(index =>
            index.Properties.Select(property => property.Name).SequenceEqual(["CallId", "MembershipId"])).IsUnique);
        var openPeriod = periods.GetIndexes().Single(index => index.IsUnique);
        Assert.Equal("[EndedAtUtc] IS NULL", openPeriod.GetFilter());

        var participantUsage = usage.GetForeignKeys().Single(foreignKey =>
            foreignKey.PrincipalEntityType.ClrType == typeof(CallParticipantRecord));
        Assert.Equal(["CallId", "MembershipId"],
            participantUsage.Properties.Select(property => property.Name).ToArray());
        Assert.Equal(["CallId", "MembershipId"],
            participantUsage.PrincipalKey.Properties.Select(property => property.Name).ToArray());
        Assert.Equal(DeleteBehavior.NoAction, participantUsage.DeleteBehavior);

        var participantCall = participants.GetForeignKeys().Single(foreignKey =>
            foreignKey.PrincipalEntityType.ClrType == typeof(CallRecord));
        var usageCall = usage.GetForeignKeys().Single(foreignKey =>
            foreignKey.PrincipalEntityType.ClrType == typeof(CallRecord));
        Assert.Equal(DeleteBehavior.Cascade, participantCall.DeleteBehavior);
        Assert.Equal(DeleteBehavior.Cascade, usageCall.DeleteBehavior);
    }

    [Fact]
    public void Persistence_contains_no_tokens_contacts_audio_or_provider_subjects()
    {
        var names = context.Model.GetEntityTypes().SelectMany(entity => entity.GetProperties()).Select(property => property.Name).ToArray();
        foreach (var forbidden in new[] { "Token", "Credential", "ExternalSubject", "Phone", "Contact", "Audio", "Sdp", "Ice", "LastSeen" })
            Assert.DoesNotContain(names, name => name.Contains(forbidden, StringComparison.OrdinalIgnoreCase));
    }

    public void Dispose() => context.Dispose();
}
