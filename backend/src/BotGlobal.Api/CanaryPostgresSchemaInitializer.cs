using BotGlobal.Identity.Infrastructure.Persistence;
using BotGlobal.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Api;

internal static class CanaryPostgresSchemaInitializer
{
    public static async Task InitializeCanaryPostgresSchemaAsync(
        this WebApplication app,
        CancellationToken cancellationToken = default)
    {
        if (!BotGlobalDatabaseOptions.IsPostgreSql(app.Configuration)
            || !BotGlobalDatabaseOptions.IsCanaryEnsureCreatedEnabled(app.Configuration))
        {
            return;
        }

        await using var scope = app.Services.CreateAsyncScope();
        var services = scope.ServiceProvider;

        await EnsureCreatedAsync<IdentityDbContext>(services, cancellationToken);
    }

    private static async Task EnsureCreatedAsync<TContext>(
        IServiceProvider services,
        CancellationToken cancellationToken)
        where TContext : DbContext
    {
        var context = services.GetRequiredService<TContext>();
        await context.Database.EnsureCreatedAsync(cancellationToken);
    }
}
