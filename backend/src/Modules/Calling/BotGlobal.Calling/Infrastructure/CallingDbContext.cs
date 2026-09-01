using BotGlobal.Calling.Domain;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling.Infrastructure;

public sealed class CallingDbContext(DbContextOptions<CallingDbContext> options) : DbContext(options)
{
    public DbSet<CallRecord> Calls => Set<CallRecord>();
    public DbSet<CallParticipantRecord> Participants => Set<CallParticipantRecord>();
    public DbSet<CallUsageReport> UsageReports => Set<CallUsageReport>();
    public DbSet<UsageCounterPeriod> UsagePeriods => Set<UsageCounterPeriod>();

    protected override void OnModelCreating(ModelBuilder model)
    {
        var calls = model.Entity<CallRecord>();
        calls.ToTable("Calls", CallingModule.DatabaseSchema);
        calls.HasKey(x => x.Id);
        calls.Property(x => x.ApplicationKey).HasMaxLength(80).IsUnicode(false).IsRequired();
        calls.Property(x => x.CreatedAtUtc).HasColumnType("datetimeoffset");
        calls.Property(x => x.AnsweredAtUtc).HasColumnType("datetimeoffset");
        calls.Property(x => x.EndedAtUtc).HasColumnType("datetimeoffset");
        calls.Property(x => x.State).HasConversion<string>().HasMaxLength(16).IsUnicode(false);
        calls.Property(x => x.Outcome).HasConversion<string>().HasMaxLength(16).IsUnicode(false);
        calls.Property(x => x.EndReason).HasMaxLength(40).IsUnicode(false);
        calls.HasIndex(x => new { x.ApplicationId, x.CreatedAtUtc });

        var participants = model.Entity<CallParticipantRecord>();
        participants.ToTable("CallParticipants", CallingModule.DatabaseSchema);
        participants.HasKey(x => x.Id);
        participants.Property(x => x.Role).HasConversion<string>().HasMaxLength(16).IsUnicode(false);
        participants.Property(x => x.DisplayNameSnapshot).HasMaxLength(160).IsRequired();
        participants.Property(x => x.JoinedAtUtc).HasColumnType("datetimeoffset");
        participants.Property(x => x.AnsweredAtUtc).HasColumnType("datetimeoffset");
        participants.HasAlternateKey(x => new { x.CallId, x.MembershipId });
        participants.HasIndex(x => new { x.MembershipId, x.CallId });
        participants.HasOne<CallRecord>().WithMany(x => x.Participants).HasForeignKey(x => x.CallId).OnDelete(DeleteBehavior.Cascade);

        var usage = model.Entity<CallUsageReport>();
        usage.ToTable("CallUsageReports", CallingModule.DatabaseSchema, table =>
        {
            table.HasCheckConstraint("CK_CallUsageReports_NonNegative", "[BytesSent] >= 0 AND [BytesReceived] >= 0 AND [ConnectedDurationSeconds] >= 0");
        });
        usage.HasKey(x => x.Id);
        usage.Property(x => x.FinalizedAtUtc).HasColumnType("datetimeoffset");
        usage.HasIndex(x => new { x.CallId, x.MembershipId }).IsUnique();
        usage.HasIndex(x => new { x.MembershipId, x.FinalizedAtUtc });
        usage.HasOne<CallRecord>().WithMany(x => x.UsageReports).HasForeignKey(x => x.CallId).OnDelete(DeleteBehavior.Cascade);
        usage.HasOne<CallParticipantRecord>().WithMany()
            .HasForeignKey(x => new { x.CallId, x.MembershipId })
            .HasPrincipalKey(x => new { x.CallId, x.MembershipId })
            .OnDelete(DeleteBehavior.NoAction);

        var periods = model.Entity<UsageCounterPeriod>();
        periods.ToTable("UsageCounterPeriods", CallingModule.DatabaseSchema);
        periods.HasKey(x => x.Id);
        periods.Property(x => x.StartedAtUtc).HasColumnType("datetimeoffset");
        periods.Property(x => x.EndedAtUtc).HasColumnType("datetimeoffset");
        periods.Property(x => x.ScheduledResetAtUtc).HasColumnType("datetimeoffset");
        periods.Property(x => x.ScheduledLocalDateTime).HasColumnType("datetime2");
        periods.Property(x => x.ScheduledTimeZoneId).HasMaxLength(100).IsUnicode(false);
        periods.Property(x => x.ResetReason).HasConversion<string>().HasMaxLength(16).IsUnicode(false);
        periods.HasIndex(x => new { x.ApplicationId, x.MembershipId, x.StartedAtUtc });
        periods.HasIndex(x => new { x.ApplicationId, x.MembershipId })
            .IsUnique().HasFilter("[EndedAtUtc] IS NULL");
    }
}
