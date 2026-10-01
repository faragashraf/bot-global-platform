using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
using SentriCam.Infrastructure.Persistence;

namespace SentriCam.Migrations.PostgreSql;

public sealed class PostgreSqlMigrationsDbContextFactory : IDesignTimeDbContextFactory<SentriCamDbContext>
{
    public SentriCamDbContext CreateDbContext(string[] args)
    {
        var connectionString = Environment.GetEnvironmentVariable("ConnectionStrings__SentriCam")
            ?? "Host=localhost;Database=sentricam;Username=postgres;Password=postgres";
        var options = new DbContextOptionsBuilder<SentriCamDbContext>()
            .UseNpgsql(
                connectionString,
                postgres => postgres.MigrationsAssembly(
                    typeof(PostgreSqlMigrationsDbContextFactory).Assembly.GetName().Name))
            .Options;
        return new SentriCamDbContext(options);
    }
}
