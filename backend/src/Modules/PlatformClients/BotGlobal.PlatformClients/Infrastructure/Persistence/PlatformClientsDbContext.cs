using BotGlobal.PlatformClients;
using BotGlobal.PlatformClients.Domain;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.PlatformClients.Infrastructure.Persistence;

public sealed class PlatformClientsDbContext(
    DbContextOptions<PlatformClientsDbContext> options)
    : DbContext(options)
{
    public DbSet<PlatformClient> Clients => Set<PlatformClient>();
    public DbSet<PlatformClientCredential> Credentials => Set<PlatformClientCredential>();
    public DbSet<PlatformClientCapability> Capabilities => Set<PlatformClientCapability>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(PlatformClientsModule.DatabaseSchema);
        modelBuilder.ApplyConfigurationsFromAssembly(
            typeof(PlatformClientsDbContext).Assembly);
        base.OnModelCreating(modelBuilder);
    }
}
