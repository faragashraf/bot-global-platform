using BotGlobal.Pairing.Domain;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Infrastructure.Persistence;

public sealed class PairingDbContext(
    DbContextOptions<PairingDbContext> options)
    : DbContext(options)
{
    public DbSet<PairingChallenge> Challenges => Set<PairingChallenge>();

    public DbSet<MobileDevice> Devices => Set<MobileDevice>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(PairingModule.DatabaseSchema);
        modelBuilder.ApplyConfigurationsFromAssembly(
            typeof(PairingDbContext).Assembly);
        base.OnModelCreating(modelBuilder);
    }
}
