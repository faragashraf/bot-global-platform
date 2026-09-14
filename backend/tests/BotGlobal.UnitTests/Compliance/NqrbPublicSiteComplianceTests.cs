using System.Text.Json;
using System.Text.RegularExpressions;

namespace BotGlobal.UnitTests.Compliance;

public sealed class NqrbPublicSiteComplianceTests
{
    [Fact]
    public void Public_deletion_page_uses_one_central_google_client_and_memory_only_token_flow()
    {
        var root = RepositoryRoot();
        var site = Path.Combine(root, "privacy-site");
        var config = File.ReadAllText(Path.Combine(site, "assets", "nqrb-config.js"));
        var behavior = File.ReadAllText(Path.Combine(site, "assets", "nqrb-account-deletion.js"));
        var page = File.ReadAllText(Path.Combine(site, "nqrb", "account-deletion", "index.html"));
        var allSiteScriptsAndMarkup = Directory.GetFiles(site, "*", SearchOption.AllDirectories)
            .Where(path => path.EndsWith(".js", StringComparison.OrdinalIgnoreCase)
                || path.EndsWith(".html", StringComparison.OrdinalIgnoreCase))
            .Select(File.ReadAllText)
            .ToArray();

        var clientIds = allSiteScriptsAndMarkup
            .SelectMany(text => Regex.Matches(
                text,
                @"[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com",
                RegexOptions.IgnoreCase).Select(match => match.Value))
            .ToArray();
        Assert.Single(clientIds);
        Assert.Contains("googleWebClientId", config);
        Assert.DoesNotContain("client_secret", string.Join('\n', allSiteScriptsAndMarkup),
            StringComparison.OrdinalIgnoreCase);
        Assert.Contains("https://accounts.google.com/gsi/client", page);
        Assert.Contains("JSON.stringify({ idToken })", behavior);
        Assert.DoesNotContain("localStorage", behavior, StringComparison.Ordinal);
        Assert.DoesNotContain("sessionStorage", behavior, StringComparison.Ordinal);
        Assert.DoesNotContain("console.", behavior, StringComparison.Ordinal);
        Assert.DoesNotContain("location.", behavior, StringComparison.Ordinal);
        Assert.True(
            behavior.IndexOf("/api/public/nqrb/account-deletion/verify", StringComparison.Ordinal)
            < behavior.IndexOf("confirmation.hidden = false", StringComparison.Ordinal));
    }

    [Fact]
    public void Cors_preserves_local_development_and_allows_only_the_configured_pages_origin()
    {
        var root = RepositoryRoot();
        using var settings = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(root, "backend", "src", "BotGlobal.Api", "appsettings.json")));
        var origins = settings.RootElement
            .GetProperty("Frontend")
            .GetProperty("AllowedOrigins")
            .EnumerateArray()
            .Select(item => item.GetString())
            .ToArray();
        var staticConfig = File.ReadAllText(Path.Combine(root, "privacy-site", "assets", "nqrb-config.js"));
        var publicHost = Regex.Match(staticConfig, @"apiBaseUrl:\s*'https://[^']+'", RegexOptions.IgnoreCase);

        Assert.Contains("http://localhost:4200", origins);
        Assert.Contains("https://faragashraf.github.io", origins);
        Assert.DoesNotContain(origins, origin => origin is null || origin.Contains('*'));
        Assert.True(publicHost.Success);
    }

    private static string RepositoryRoot()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !Directory.Exists(Path.Combine(directory.FullName, "privacy-site")))
        {
            directory = directory.Parent;
        }

        return directory?.FullName
            ?? throw new InvalidOperationException("Repository root containing privacy-site was not found.");
    }
}
