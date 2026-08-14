using BotGlobal.PlatformClients.Application.Credentials;
using BotGlobal.PlatformClients.Application.Queries;

namespace BotGlobal.UnitTests.PlatformClients;

public sealed class PlatformClientCredentialLifecycleContractTests
{
    [Fact]
    public void Rotation_result_contains_one_time_secret()
        => Assert.Contains(nameof(RotatedPlatformClientCredential.ClientSecret), typeof(RotatedPlatformClientCredential).GetProperties().Select(x => x.Name));

    [Fact]
    public void List_contract_does_not_expose_secret_or_hash()
    {
        var names = typeof(PlatformClientCredentialListItem).GetProperties().Select(x => x.Name).ToArray();
        Assert.DoesNotContain(names, x => x.Contains("Secret", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(names, x => x.Contains("Hash", StringComparison.OrdinalIgnoreCase));
    }
}
