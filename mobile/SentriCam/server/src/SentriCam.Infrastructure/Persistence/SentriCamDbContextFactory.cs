using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
using SentriCam.Contracts.Hub;

namespace SentriCam.Infrastructure.Persistence;

public sealed class SentriCamDbContextFactory : IDesignTimeDbContextFactory<SentriCamDbContext>
{
    public SentriCamDbContext CreateDbContext(string[] args)
    {
        var provider = Environment.GetEnvironmentVariable("Database__Provider")
            ?? HubDatabaseProviders.SqlServer;
        var connectionString = Environment.GetEnvironmentVariable("ConnectionStrings__SentriCam")
            ?? "Server=localhost,1433;Database=SentriCam;Integrated Security=True;Encrypt=True;TrustServerCertificate=True";
        var options = new DbContextOptionsBuilder<SentriCamDbContext>()
            .ConfigureSentriCamProvider(provider, connectionString)
            .Options;

        return new SentriCamDbContext(options);
    }
}
