using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.Identity.Infrastructure.Persistence;

public sealed class IdentityDbContextFactory
    : IDesignTimeDbContextFactory<IdentityDbContext>
{
    public IdentityDbContext CreateDbContext(
        string[] args)
    {
        var connectionString =
            Environment.GetEnvironmentVariable(
                "BOTGLOBAL_IDENTITY_CONNECTION")
            ?? "Server=localhost;Database=BotGlobal.Identity;Trusted_Connection=True;TrustServerCertificate=True";

        var options =
            new DbContextOptionsBuilder<IdentityDbContext>()
                .UseSqlServer(
                    connectionString,
                    sql =>
                        sql.MigrationsHistoryTable(
                            "__EFMigrationsHistory",
                            "identity"))
                .Options;

        return new IdentityDbContext(options);
    }
}
