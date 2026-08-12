using BotGlobal.Communication.Domain.Calls;
using BotGlobal.Communication.Domain.Conversations;
using BotGlobal.Communication.Domain.Messaging;
using BotGlobal.Communication.Domain.Preferences;
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

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema("communication");

        modelBuilder.ApplyConfigurationsFromAssembly(
            typeof(CommunicationDbContext).Assembly);

        base.OnModelCreating(modelBuilder);
    }
}
