using BotGlobal.Communication.Domain.Calls;
using BotGlobal.Communication.Domain.Conversations;
using BotGlobal.Communication.Domain.Messaging;
using BotGlobal.Communication.Domain.Preferences;
using BotGlobal.Communication.Domain.Chat;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Communication.Infrastructure.Persistence;

public sealed class CommunicationDbContext(
    DbContextOptions<CommunicationDbContext> options)
    : DbContext(options)
{
    public DbSet<Conversation> Conversations => Set<Conversation>();

    public DbSet<ConversationParticipant> ConversationParticipants =>
        Set<ConversationParticipant>();

    public DbSet<Message> Messages => Set<Message>();

    public DbSet<MessageReceipt> MessageReceipts =>
        Set<MessageReceipt>();

    public DbSet<UserCommunicationPreference> UserCommunicationPreferences =>
        Set<UserCommunicationPreference>();

    public DbSet<CallSession> CallSessions => Set<CallSession>();
    public DbSet<ChatConversation> ChatConversations => Set<ChatConversation>();
    public DbSet<ChatMessage> ChatMessages => Set<ChatMessage>();
    public DbSet<ChatVoiceTransfer> ChatVoiceTransfers => Set<ChatVoiceTransfer>();
    public DbSet<ChatReceipt> ChatReceipts => Set<ChatReceipt>();
    public DbSet<ChatDispatch> ChatDispatches => Set<ChatDispatch>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema("communication");

        modelBuilder.ApplyConfigurationsFromAssembly(
            typeof(CommunicationDbContext).Assembly);

        // SQLite is a local test tool only. PostgreSQL's deterministic C collation
        // preserves opaque identity equality without normalization or case folding.
        if (Database.ProviderName == "Microsoft.EntityFrameworkCore.Sqlite")
        {
            foreach (var entity in modelBuilder.Model.GetEntityTypes()
                         .Where(x => x.ClrType.Namespace == typeof(ChatConversation).Namespace))
            foreach (var property in entity.GetProperties())
                if (property.GetCollation() == "C") property.SetCollation("BINARY");
        }

        base.OnModelCreating(modelBuilder);
    }
}
