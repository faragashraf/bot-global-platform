using BotGlobal.Games.Application.Startup;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Games;

public sealed class ApplicationVersionPolicyTests
{
    [Fact]
    public void Uses_server_owned_platform_policy_without_store_scraping()
    {
        var reader = new ApplicationVersionPolicyReader(
            Options.Create(
                new FamilyGamesVersionPolicyOptions
                {
                    Android = new PlatformVersionPolicy
                    {
                        LatestVersion = "2.0.0",
                        MinimumSupportedVersion = "1.5.0",
                        StoreDestination = "market://configured-destination"
                    },
                    Ios = new PlatformVersionPolicy
                    {
                        LatestVersion = "3.0.0",
                        MinimumSupportedVersion = "2.5.0"
                    }
                }));

        var android = reader.Read("android", "1.0.0");
        var ios = reader.Read("ios", "2.0.0");

        Assert.Equal("2.0.0", android.LatestVersion);
        Assert.Equal("1.5.0", android.MinimumSupportedVersion);
        Assert.Equal("3.0.0", ios.LatestVersion);
        Assert.Equal("2.5.0", ios.MinimumSupportedVersion);
    }
}
