using BotGlobal.Catalog.Domain;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Catalog.Infrastructure;

public sealed class CatalogDbContext(DbContextOptions<CatalogDbContext> options) : DbContext(options)
{
    public const string Schema = "catalog";
    public const string MigrationHistoryTable = "__EFMigrationsHistory";

    public DbSet<Product> Products => Set<Product>();
    public DbSet<ProductLocalization> ProductLocalizations => Set<ProductLocalization>();
    public DbSet<ProductMedia> ProductMedia => Set<ProductMedia>();
    public DbSet<ProductLink> ProductLinks => Set<ProductLink>();
    public DbSet<ProductRelease> ProductReleases => Set<ProductRelease>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(Schema);
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(CatalogDbContext).Assembly);
    }
}
