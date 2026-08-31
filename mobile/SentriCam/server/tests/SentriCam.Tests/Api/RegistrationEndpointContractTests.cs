namespace SentriCam.Tests.Api;

public sealed class RegistrationEndpointContractTests
{
    [Fact]
    public void RegistrationEndpointRemainsAnonymousVersionedBoundedAndDocumented()
    {
        var source = File.ReadAllText(RepositoryFile(
            "src",
            "SentriCam.Api",
            "Controllers",
            "DeviceRegistrationsController.cs"));

        Assert.Contains("[ApiVersion(1.0)]", source, StringComparison.Ordinal);
        Assert.Contains("[Route(\"api/v{version:apiVersion}/devices\")]", source, StringComparison.Ordinal);
        Assert.Contains("[AllowAnonymous]", source, StringComparison.Ordinal);
        Assert.Contains("[EnableRateLimiting", source, StringComparison.Ordinal);
        Assert.Contains("[RequestSizeLimit(65_536)]", source, StringComparison.Ordinal);
        Assert.Contains("[HttpPost(\"registrations\")]", source, StringComparison.Ordinal);
        Assert.Contains("ProducesResponseType<RegistrationResult>", source, StringComparison.Ordinal);
        Assert.Contains("new RegisterDevice(request)", source, StringComparison.Ordinal);
    }

    private static string RepositoryFile(params string[] segments)
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !File.Exists(Path.Combine(directory.FullName, "SentriCam.Server.sln")))
        {
            directory = directory.Parent;
        }

        Assert.NotNull(directory);
        return Path.Combine([directory!.FullName, .. segments]);
    }
}
