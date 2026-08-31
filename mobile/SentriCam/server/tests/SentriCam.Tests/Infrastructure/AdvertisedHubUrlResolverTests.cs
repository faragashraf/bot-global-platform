using System.Net;
using Microsoft.Extensions.Options;
using SentriCam.Infrastructure.Hub;

namespace SentriCam.Tests.Infrastructure;

public sealed class AdvertisedHubUrlResolverTests
{
    [Theory]
    [InlineData("https://hub.example", "https://hub.example")]
    [InlineData("https://10.20.30.40", "https://10.20.30.40")]
    [InlineData("https://hub.example:8443/", "https://hub.example:8443")]
    [InlineData("  HTTPS://Hub.Example:443/  ", "https://hub.example")]
    public void ExplicitHttpsOverrideIsHighestPrecedenceAndNormalized(string configured, string expected)
    {
        var resolver = Create(
            configured,
            hostName: "fallback.example",
            IPAddress.Parse("192.0.2.44"));

        var result = resolver.Resolve();

        Assert.Equal(expected, Authority(result));
    }

    [Theory]
    [InlineData("not a URI")]
    [InlineData("https://hub.example/api")]
    [InlineData("https://hub.example?source=request")]
    public void InvalidOverrideIsRejected(string configured)
    {
        var resolver = Create(configured, hostName: "ignored.local");

        var exception = Assert.Throws<InvalidOperationException>(() => resolver.Resolve());

        Assert.Contains("AdvertisedUrl", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void OverrideWithCredentialsIsRejected()
    {
        var resolver = Create("https://operator:secret@hub.example", hostName: "ignored.local");

        Assert.Throws<InvalidOperationException>(() => resolver.Resolve());
    }

    [Fact]
    public void HttpOverrideIsRejectedInsteadOfProducingAnInvalidPairingUrl()
    {
        var resolver = Create("http://hub.example", hostName: "ignored.local");

        Assert.Throws<InvalidOperationException>(() => resolver.Resolve());
    }

    [Fact]
    public void UsableLanIPv4IsPreferredOverMacOsLocalHostName()
    {
        var resolver = Create(
            configured: null,
            hostName: "example-mac.local",
            IPAddress.Parse("192.0.2.44"));

        var result = resolver.Resolve();

        Assert.Equal("https://192.0.2.44", Authority(result));
        Assert.Equal(Uri.UriSchemeHttps, result.Scheme);
        Assert.True(result.IsDefaultPort);
        Assert.DoesNotContain(".local", result.AbsoluteUri, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("5173", result.AbsoluteUri, StringComparison.Ordinal);
    }

    [Fact]
    public void HostNameFallbackIsUsedWhenNoUsableLanIPv4Exists()
    {
        var resolver = Create(configured: null, hostName: "cedar-hub.local");

        var result = resolver.Resolve();

        Assert.Equal("https://cedar-hub.local", Authority(result));
        Assert.True(result.IsDefaultPort);
        Assert.DoesNotContain("5173", result.AbsoluteUri, StringComparison.Ordinal);
    }

    [Fact]
    public void LoopbackAddressIsRejected()
    {
        var resolver = Create(
            configured: null,
            hostName: "fallback.example",
            IPAddress.Loopback);

        var result = resolver.Resolve();

        Assert.Equal("https://fallback.example", Authority(result));
    }

    [Theory]
    [InlineData("169.254.10.20")]
    [InlineData("0.10.20.30")]
    [InlineData("224.0.0.1")]
    [InlineData("255.255.255.255")]
    public void LinkLocalOrOtherwiseUnusableAddressIsRejected(string address)
    {
        var resolver = Create(
            configured: null,
            hostName: "fallback.example",
            IPAddress.Parse(address));

        var result = resolver.Resolve();

        Assert.Equal("https://fallback.example", Authority(result));
    }

    [Fact]
    public void MultipleViableAddressesPreserveDeterministicIdentitySourceOrder()
    {
        var resolver = Create(
            configured: null,
            hostName: null,
            IPAddress.Loopback,
            IPAddress.Parse("198.51.100.25"),
            IPAddress.Parse("192.0.2.40"),
            IPAddress.Parse("198.51.100.25"));

        var result = resolver.Resolve();

        Assert.Equal(Uri.UriSchemeHttps, result.Scheme);
        Assert.Equal("198.51.100.25", result.Host);
        Assert.False(IPAddress.IsLoopback(IPAddress.Parse(result.Host)));
        Assert.True(result.IsDefaultPort);
        Assert.DoesNotContain("5173", result.AbsoluteUri, StringComparison.Ordinal);
    }

    [Fact]
    public void MissingUsableIdentityFailsWithConfigurationGuidance()
    {
        var resolver = Create(configured: null, hostName: null, IPAddress.Loopback);

        var exception = Assert.Throws<InvalidOperationException>(() => resolver.Resolve());

        Assert.Contains("SentriCam:Hub:AdvertisedUrl", exception.Message, StringComparison.Ordinal);
    }

    private static AdvertisedHubUrlResolver Create(
        string? configured,
        string? hostName,
        params IPAddress[] addresses) => new(
            Options.Create(new AdvertisedHubUrlOptions { AdvertisedUrl = configured }),
            new StubIdentitySource(hostName, addresses));

    private static string Authority(Uri uri) => uri.GetComponents(
        UriComponents.SchemeAndServer,
        UriFormat.UriEscaped);

    private sealed class StubIdentitySource(
        string? hostName,
        IReadOnlyCollection<IPAddress> addresses) : ILocalHubIdentitySource
    {
        public string? GetHostName() => hostName;

        public IReadOnlyCollection<IPAddress> GetLanIPv4Addresses() => addresses;
    }
}
