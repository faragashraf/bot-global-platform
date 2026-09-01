using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.Calling.Infrastructure;

public sealed class CallingDesignTimeDbContextFactory : IDesignTimeDbContextFactory<CallingDbContext>
{
    public CallingDbContext CreateDbContext(string[] args)
    {
        var connection = Environment.GetEnvironmentVariable("CALLING_DESIGNTIME_CONNECTION_STRING")
            ?? "Server=design-time.invalid;Database=BotGlobalCallingDesign;Integrated Security=True;TrustServerCertificate=True;Connect Timeout=1;";
        var options = new DbContextOptionsBuilder<CallingDbContext>()
            .UseSqlServer(connection, sql => sql.MigrationsHistoryTable(CallingModule.MigrationsHistoryTableName, CallingModule.DatabaseSchema))
            .Options;
        return new CallingDbContext(options);
    }
}
