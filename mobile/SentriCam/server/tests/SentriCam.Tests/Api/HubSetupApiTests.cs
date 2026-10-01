using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using SentriCam.Contracts.Hub;

namespace SentriCam.Tests.Api;

public sealed class HubSetupApiTests
{
    [Fact]
    public async Task FreshDevelopmentProfileReturnsNotStartedHomeSqliteState()
    {
        var root = Path.Combine(Path.GetTempPath(), $"sentricam-api-first-run-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        try
        {
            await using var app = new WebApplicationFactory<Program>()
                .WithWebHostBuilder(builder =>
                {
                    builder.UseEnvironment("Development");
                    builder.UseSetting("SentriCam:Home", root);
                    builder.UseSetting("SentriCam:FreshInstall", "true");
                    builder.UseSetting(
                        "ConnectionStrings:SentriCam",
                        "Server=ambient;Database=ambient;Integrated Security=True");
                    builder.UseSetting(
                        "Authentication:Jwt:SigningKey",
                        "ambient-signing-key-with-at-least-thirty-two-characters");
                });
            using var client = app.CreateClient();

            var state = await client.GetFromJsonAsync<HubSetupState>(
                "/api/v1/hub/setup",
                TestContext.Current.CancellationToken);

            Assert.NotNull(state);
            Assert.False(state!.IsConfigured);
            Assert.Null(state.ConfiguredAtUtc);
            Assert.Equal(HubSetupStates.NotStarted, state.State);
            Assert.Equal(HubModes.Home, state.Draft.Mode);
            Assert.Equal(HubDatabaseProviders.Sqlite, state.Draft.Database.Provider);
            Assert.Null(state.Draft.Database.DatabaseName);
            Assert.DoesNotContain(root, JsonSerializer.Serialize(state), StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
