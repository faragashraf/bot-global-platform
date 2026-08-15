using BotGlobal.Pairing.Security;

namespace BotGlobal.UnitTests.Pairing;

public sealed class MobileDeviceCredentialServiceTests
{
    [Fact]
    public void Generate_ReturnsCredentialAndSha256Hash()
    {
        var service = new MobileDeviceCredentialService();

        var result = service.Generate();

        Assert.False(string.IsNullOrWhiteSpace(result.PlainText));
        Assert.Equal(32, result.Hash.Length);
        Assert.Equal(
            result.Hash,
            service.Hash(result.PlainText));
    }

    [Fact]
    public void Generate_ReturnsDifferentCredentials()
    {
        var service = new MobileDeviceCredentialService();

        var first = service.Generate();
        var second = service.Generate();

        Assert.NotEqual(first.PlainText, second.PlainText);
        Assert.NotEqual(first.Hash, second.Hash);
    }
}
