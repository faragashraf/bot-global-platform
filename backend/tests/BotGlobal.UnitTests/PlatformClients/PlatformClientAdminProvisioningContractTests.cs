using BotGlobal.PlatformClients.Application.Provisioning;
using BotGlobal.PlatformClients.Endpoints;

namespace BotGlobal.UnitTests.PlatformClients;

public sealed class PlatformClientAdminProvisioningContractTests
{
    [Fact]
    public void Create_request_cannot_supply_client_secret()
    {
        var properties =
            typeof(CreatePlatformClientRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Contains(
            nameof(CreatePlatformClientRequest.ClientKey),
            properties);

        Assert.Contains(
            nameof(CreatePlatformClientRequest.DisplayName),
            properties);

        Assert.Contains(
            nameof(CreatePlatformClientRequest.Capabilities),
            properties);

        Assert.DoesNotContain(
            properties,
            property =>
                property.Contains(
                    "Secret",
                    StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Creation_result_contains_one_time_secret()
    {
        var properties =
            typeof(CreatedPlatformClient)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Contains(
            nameof(CreatedPlatformClient.ClientSecret),
            properties);
    }

    [Fact]
    public void Provisioning_command_is_generic()
    {
        var properties =
            typeof(CreatePlatformClientCommand)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Equal(
            new[]
            {
                "ClientKey",
                "DisplayName",
                "Capabilities"
            },
            properties);
    }
}
