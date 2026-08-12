using BotGlobal.Communication.Domain.Calls;
using BotGlobal.Communication.Domain.Conversations;
using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Domain.Messaging;
using BotGlobal.Communication.Domain.Preferences;
using BotGlobal.Communication.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;

namespace BotGlobal.UnitTests.Communication.Persistence;

public sealed class CommunicationPersistenceModelTests
{
    private static CommunicationDbContext CreateContext()
    {
        var options =
            new DbContextOptionsBuilder<CommunicationDbContext>()
                .UseSqlServer(
                    "Server=localhost;Database=BotGlobal.Communication.ModelTests;"
                    + "User Id=sa;Password=NotUsedByModelTests123!;"
                    + "Encrypt=False;TrustServerCertificate=True")
                .Options;

        return new CommunicationDbContext(options);
    }

    [Fact]
    public void Every_entity_uses_communication_schema()
    {
        using var context = CreateContext();

        var schemas = context.Model
            .GetEntityTypes()
            .Select(entity => entity.GetSchema())
            .Where(schema => schema is not null)
            .Select(schema => schema!)
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        Assert.Equal(
            new[] { "communication" },
            schemas);
    }

    [Fact]
    public void Expected_tables_are_mapped()
    {
        using var context = CreateContext();

        var tables = context.Model
            .GetEntityTypes()
            .Select(entity => entity.GetTableName())
            .Where(table => table is not null)
            .Select(table => table!)
            .OrderBy(name => name, StringComparer.Ordinal)
            .ToArray();

        Assert.Equal(
            new[]
            {
                "CallSessions",
                "ConversationParticipants",
                "Conversations",
                "MessageReceipts",
                "Messages",
                "UserCommunicationPreferences"
            },
            tables);
    }

    [Fact]
    public void Direct_conversation_key_is_unique_and_filtered()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(typeof(Conversation))!;

        var index = entity
            .GetIndexes()
            .Single(index =>
                index.GetDatabaseName()
                == "UX_Conversations_DirectKey");

        Assert.True(index.IsUnique);
        Assert.Equal(
            "[DirectKey] IS NOT NULL",
            index.GetFilter());
    }

    [Fact]
    public void Participant_key_is_conversation_and_user()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(
                typeof(ConversationParticipant))!;

        var key = entity.FindPrimaryKey()!;

        Assert.Equal(
            ["ConversationId", "UserId"],
            key.Properties
                .Select(property => property.Name)
                .ToArray());
    }

    [Fact]
    public void Message_sequence_is_database_generated_and_unique_per_conversation()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(typeof(Message))!;

        var sequence = entity
            .FindProperty(nameof(Message.SequenceNumber))!;

        Assert.Equal(
            ValueGenerated.OnAdd,
            sequence.ValueGenerated);

        var index = entity
            .GetIndexes()
            .Single(index =>
                index.GetDatabaseName()
                == "UX_Messages_Conversation_Sequence");

        Assert.True(index.IsUnique);

        Assert.Equal(
            ["ConversationId", "SequenceNumber"],
            index.Properties
                .Select(property => property.Name)
                .ToArray());
    }

    [Fact]
    public void Client_message_id_is_unique_per_sender()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(typeof(Message))!;

        var index = entity
            .GetIndexes()
            .Single(index =>
                index.GetDatabaseName()
                == "UX_Messages_Sender_ClientMessageId");

        Assert.True(index.IsUnique);

        Assert.Equal(
            ["SenderUserId", "ClientMessageId"],
            index.Properties
                .Select(property => property.Name)
                .ToArray());
    }

    [Fact]
    public void Receipt_key_is_message_and_user()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(typeof(MessageReceipt))!;

        Assert.Equal(
            ["MessageId", "UserId"],
            entity.FindPrimaryKey()!
                .Properties
                .Select(property => property.Name)
                .ToArray());
    }

    [Fact]
    public void User_preferences_are_keyed_only_by_platform_user_id()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(
                typeof(UserCommunicationPreference))!;

        Assert.Equal(
            ["UserId"],
            entity.FindPrimaryKey()!
                .Properties
                .Select(property => property.Name)
                .ToArray());
    }

    [Fact]
    public void Communication_model_has_no_foreign_key_to_identity_module()
    {
        using var context = CreateContext();

        var foreignKeys = context.Model
            .GetEntityTypes()
            .SelectMany(entity => entity.GetForeignKeys())
            .ToArray();

        Assert.All(
            foreignKeys,
            foreignKey =>
            {
                var principalClr =
                    foreignKey.PrincipalEntityType.ClrType;

                Assert.StartsWith(
                    "BotGlobal.Communication.",
                    principalClr.Namespace);
            });
    }

    [Fact]
    public void Call_session_client_id_is_unique_per_caller()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(typeof(CallSession))!;

        var index = entity
            .GetIndexes()
            .Single(index =>
                index.GetDatabaseName()
                == "UX_CallSessions_Caller_ClientCallId");

        Assert.True(index.IsUnique);

        Assert.Equal(
            ["CallerUserId", "ClientCallId"],
            index.Properties
                .Select(property => property.Name)
                .ToArray());
    }

    [Fact]
    public void Model_contains_required_check_constraints()
    {
        using var context = CreateContext();

        var designTimeModel = context
            .GetService<IDesignTimeModel>()
            .Model;

        var constraintNames = designTimeModel
            .GetEntityTypes()
            .SelectMany(entity =>
                entity.GetCheckConstraints())
            .Select(constraint => constraint.Name)
            .ToHashSet(
                StringComparer.Ordinal);

        string[] expected =
        [
            "CK_Conversations_Type",
            "CK_Conversations_Shape",
            "CK_Conversations_ActivityTime",
            "CK_ConversationParticipants_Role",
            "CK_ConversationParticipants_MembershipTime",
            "CK_Messages_Kind",
            "CK_Messages_Content",
            "CK_MessageReceipts_ReadRequiresDelivery",
            "CK_MessageReceipts_TimeOrder",
            "CK_CallSessions_Kind",
            "CK_CallSessions_Status",
            "CK_CallSessions_EndReason",
            "CK_CallSessions_DifferentUsers",
            "CK_CallSessions_TimeOrder"
        ];

        foreach (var name in expected)
        {
            Assert.Contains(name, constraintNames);
        }
    }

    [Theory]
    [InlineData(typeof(Conversation), "CreatedByUserId")]
    [InlineData(typeof(ConversationParticipant), "UserId")]
    [InlineData(typeof(Message), "SenderUserId")]
    [InlineData(typeof(MessageReceipt), "UserId")]
    [InlineData(typeof(UserCommunicationPreference), "UserId")]
    [InlineData(typeof(CallSession), "CallerUserId")]
    [InlineData(typeof(CallSession), "CalleeUserId")]
    public void External_user_identifier_columns_are_string_128(
        Type entityType,
        string propertyName)
    {
        using var context = CreateContext();

        var property = context.Model
            .FindEntityType(entityType)!
            .FindProperty(propertyName)!;

        Assert.Equal(typeof(string), property.ClrType);
        Assert.Equal(ExternalUserId.MaxLength, property.GetMaxLength());
        Assert.False(property.IsNullable);
    }

}
