using BotGlobal.Pairing.Security;
using Microsoft.Extensions.Primitives;

namespace BotGlobal.UnitTests.Pairing;

public sealed class MobileDeviceAuthorizationTests
{
    [Fact]
    public void TryReadCredential_AcceptsDeviceScheme()
    {
        var result =
            MobileDeviceAuthorization.TryReadCredential(
                new StringValues(
                    "Device credential-value"),
                out var credential);

        Assert.True(result);
        Assert.Equal(
            "credential-value",
            credential);
    }

    [Theory]
    [InlineData("")]
    [InlineData("Bearer token")]
    [InlineData("Device")]
    [InlineData("Device ")]
    public void TryReadCredential_RejectsInvalidHeader(
        string header)
    {
        var result =
            MobileDeviceAuthorization.TryReadCredential(
                new StringValues(header),
                out _);

        Assert.False(result);
    }
}
