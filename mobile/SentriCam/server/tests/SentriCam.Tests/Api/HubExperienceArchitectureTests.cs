namespace SentriCam.Tests.Api;

public sealed class HubExperienceArchitectureTests
{
    [Fact]
    public void HubEndpointsRemainThinAndDelegateToCapabilityEngines()
    {
        var source = File.ReadAllText(RepositoryFile(
            "src",
            "SentriCam.Api",
            "Controllers",
            "HubController.cs"));

        Assert.Contains("IHubSetupEngine setupEngine", source, StringComparison.Ordinal);
        Assert.Contains("IHubStatusProbe statusProbe", source, StringComparison.Ordinal);
        Assert.Contains("IPairingEngine pairingEngine", source, StringComparison.Ordinal);
        Assert.DoesNotContain("new SentriCamDbContext", source, StringComparison.Ordinal);
        Assert.DoesNotContain("UseSqlite", source, StringComparison.Ordinal);
        Assert.DoesNotContain("UseSqlServer", source, StringComparison.Ordinal);
        Assert.DoesNotContain("UseNpgsql", source, StringComparison.Ordinal);
    }

    [Fact]
    public void SetupContractsDoNotReturnSigningKeysOrConnectionStrings()
    {
        var source = File.ReadAllText(RepositoryFile(
            "src",
            "SentriCam.Contracts",
            "Hub",
            "HubContracts.cs"));

        Assert.DoesNotContain("SigningKey", source, StringComparison.Ordinal);
        Assert.DoesNotContain("ConnectionString", source, StringComparison.Ordinal);
        Assert.Contains("PairingSession", source, StringComparison.Ordinal);
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
