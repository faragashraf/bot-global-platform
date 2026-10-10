using BotGlobal.Communication.Application.Presence;
using BotGlobal.Contracts.Mobile;
using Microsoft.Extensions.Configuration;
using System.Text.Json;

namespace BotGlobal.Communication.Infrastructure.Presence;

public sealed class FirebasePresenceProfile
{
    public bool Enabled { get; set; }
    public string ApplicationKey { get; set; } = BotGlobalApplications.Nqrb;
    public string ProjectId { get; set; } = string.Empty;
    public string DatabaseNamespace { get; set; } = string.Empty;
    public string DatabaseUrl { get; set; } = string.Empty;
    public string AllowedDatabaseHost { get; set; } = string.Empty;
    public string CredentialJson { get; set; } = string.Empty;
    public bool Emulator { get; set; }
    public int MaxLeasesPerSession { get; set; } = 8;

    public static IReadOnlyList<FirebasePresenceProfile> ReadAll(IConfiguration configuration)
    {
        var section = configuration.GetSection($"{PresenceOptions.SectionName}:Firebase");
        var profiles = section.GetSection("Profiles").Get<List<FirebasePresenceProfile>>();
        return profiles is { Count: > 0 }
            ? profiles
            : [section.Get<FirebasePresenceProfile>() ?? new FirebasePresenceProfile()];
    }

    public bool IsValid(out string reason)
    {
        reason = string.Empty;
        if (!Enabled) return true;
        if (string.IsNullOrWhiteSpace(ApplicationKey) || ApplicationKey.Length > 64 ||
            ApplicationKey.Any(character => !char.IsAsciiLetterOrDigit(character) && character is not '-' and not '_'))
            return Fail("Presence application key is invalid.", out reason);
        if (MaxLeasesPerSession is < 1 or > 16)
            return Fail("Presence lease capacity is invalid.", out reason);
        if (!Uri.TryCreate(DatabaseUrl, UriKind.Absolute, out var uri) ||
            !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) ||
            !string.IsNullOrEmpty(uri.Fragment) || uri.AbsolutePath != "/")
            return Fail("Presence database URL must be an origin without credentials, path, query, or fragment.", out reason);
        if (Emulator)
        {
            if (!ProjectId.StartsWith("demo-", StringComparison.Ordinal) || !uri.IsLoopback ||
                uri.Scheme != Uri.UriSchemeHttp || uri.Port is < 1 or > 65535 ||
                !string.Equals(uri.IdnHost, AllowedDatabaseHost.Trim().ToLowerInvariant(), StringComparison.Ordinal) ||
                !string.Equals(DatabaseNamespace, ProjectId, StringComparison.Ordinal) ||
                !string.IsNullOrWhiteSpace(CredentialJson))
                return Fail("Presence emulator requires a demo project, loopback HTTP, and no credential.", out reason);
        }
        else if (uri.Scheme != Uri.UriSchemeHttps ||
                 !uri.IsDefaultPort ||
                 string.IsNullOrWhiteSpace(ProjectId) || string.IsNullOrWhiteSpace(CredentialJson) ||
                 string.IsNullOrWhiteSpace(DatabaseNamespace) ||
                 string.IsNullOrWhiteSpace(AllowedDatabaseHost) ||
                 !string.Equals(uri.IdnHost, AllowedDatabaseHost.Trim().ToLowerInvariant(), StringComparison.Ordinal) ||
                 !IsApprovedProductionHost(uri.IdnHost, ProjectId, DatabaseNamespace) ||
                 !IsSupportedServiceAccount(CredentialJson, ProjectId))
        {
            return Fail("Enabled presence requires an exact HTTPS project/namespace host and supported service-account credential.", out reason);
        }
        return true;
    }

    private static bool IsApprovedProductionHost(string host, string projectId, string databaseNamespace)
    {
        if (databaseNamespace != projectId && databaseNamespace != $"{projectId}-default-rtdb") return false;
        if (string.Equals(host, $"{databaseNamespace}.firebaseio.com", StringComparison.Ordinal)) return true;
        var suffix = ".firebasedatabase.app";
        if (!host.EndsWith(suffix, StringComparison.Ordinal)) return false;
        var prefix = host[..^suffix.Length];
        return prefix.StartsWith(databaseNamespace + ".", StringComparison.Ordinal) &&
            prefix[(databaseNamespace.Length + 1)..].All(character =>
                char.IsAsciiLetterOrDigit(character) || character is '-');
    }

    private static bool IsSupportedServiceAccount(string json, string projectId)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object ||
                !root.TryGetProperty("type", out var type) || type.GetString() != "service_account" ||
                !root.TryGetProperty("project_id", out var credentialProject) || credentialProject.GetString() != projectId ||
                !root.TryGetProperty("client_email", out var email) || string.IsNullOrWhiteSpace(email.GetString()) ||
                !root.TryGetProperty("private_key", out var key) || string.IsNullOrWhiteSpace(key.GetString()) ||
                !root.TryGetProperty("token_uri", out var tokenUri) ||
                tokenUri.GetString() != "https://oauth2.googleapis.com/token") return false;
            return IsOptionalEndpoint(root, "auth_uri", "https://accounts.google.com/o/oauth2/auth") &&
                IsOptionalGoogleApisEndpoint(root, "auth_provider_x509_cert_url", "/oauth2/v1/certs") &&
                IsOptionalGoogleApisEndpoint(root, "client_x509_cert_url", "/robot/v1/metadata/x509/");
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static bool IsOptionalEndpoint(JsonElement root, string property, string expected) =>
        !root.TryGetProperty(property, out var value) || value.GetString() == expected;

    private static bool IsOptionalGoogleApisEndpoint(JsonElement root, string property, string requiredPathPrefix)
    {
        if (!root.TryGetProperty(property, out var value)) return true;
        return Uri.TryCreate(value.GetString(), UriKind.Absolute, out var endpoint) &&
            endpoint.Scheme == Uri.UriSchemeHttps && endpoint.IsDefaultPort &&
            endpoint.IdnHost == "www.googleapis.com" && endpoint.AbsolutePath.StartsWith(requiredPathPrefix, StringComparison.Ordinal) &&
            string.IsNullOrEmpty(endpoint.Query) && string.IsNullOrEmpty(endpoint.Fragment) && string.IsNullOrEmpty(endpoint.UserInfo);
    }

    private static bool Fail(string message, out string reason)
    {
        reason = message;
        return false;
    }
}
