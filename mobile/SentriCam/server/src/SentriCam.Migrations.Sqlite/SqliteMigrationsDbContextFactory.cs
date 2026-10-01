using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
using SentriCam.Infrastructure.Persistence;

namespace SentriCam.Migrations.Sqlite;

public sealed class SqliteMigrationsDbContextFactory : IDesignTimeDbContextFactory<SentriCamDbContext>
{
    public SentriCamDbContext CreateDbContext(string[] args)
    {
        var connectionString = Environment.GetEnvironmentVariable("ConnectionStrings__SentriCam")
            ?? "Data Source=sentricam-migrations.db";
        var options = new DbContextOptionsBuilder<SentriCamDbContext>()
            .UseSqlite(
                connectionString,
                sqlite => sqlite.MigrationsAssembly(typeof(SqliteMigrationsDbContextFactory).Assembly.GetName().Name))
            .Options;
        return new SentriCamDbContext(options);
    }
}
