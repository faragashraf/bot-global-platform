using BotGlobal.Contracts.Notifications;
using Microsoft.Extensions.Options;

namespace BotGlobal.Communication.Application.MobileNotifications.Push;

internal enum ApplicationPushProviderResolutionKind
{
    Ready = 1,
    Disabled = 2,
    Missing = 3
}

internal sealed record ResolvedApplicationPushProvider(
    NotificationApplicationContext Application,
    string Provider,
    string ConfigurationReference,
    string? FirebaseProjectId,
    string? AndroidPackageName,
    string? AppleBundleId);

internal sealed record ApplicationPushProviderResolution(
    ApplicationPushProviderResolutionKind Kind,
    ResolvedApplicationPushProvider? Configuration);

internal interface IApplicationPushProviderResolver
{
    ApplicationPushProviderResolution Resolve(
        NotificationApplicationContext application,
        string provider);
}

internal sealed class ConfigurationApplicationPushProviderResolver(
    IOptions<ApplicationPushProviderOptions> options)
    : IApplicationPushProviderResolver
{
    public ApplicationPushProviderResolution Resolve(
        NotificationApplicationContext application,
        string provider)
    {
        ArgumentNullException.ThrowIfNull(application);
        ArgumentException.ThrowIfNullOrWhiteSpace(provider);

        var normalizedProvider = provider.Trim().ToLowerInvariant();
        var match = options.Value.Providers.SingleOrDefault(candidate =>
            candidate.ApplicationId == application.ApplicationId
            && string.Equals(
                candidate.Provider?.Trim(),
                normalizedProvider,
                StringComparison.OrdinalIgnoreCase));

        if (match is null)
        {
            return new ApplicationPushProviderResolution(
                ApplicationPushProviderResolutionKind.Missing,
                null);
        }

        var resolved = new ResolvedApplicationPushProvider(
            application,
            normalizedProvider,
            match.ConfigurationReference.Trim(),
            Normalize(match.FirebaseProjectId),
            Normalize(match.AndroidPackageName),
            Normalize(match.AppleBundleId));

        return new ApplicationPushProviderResolution(
            match.Enabled
                ? ApplicationPushProviderResolutionKind.Ready
                : ApplicationPushProviderResolutionKind.Disabled,
            resolved);
    }

    private static string? Normalize(string? value)
    {
        var normalized = value?.Trim();
        return string.IsNullOrEmpty(normalized) ? null : normalized;
    }
}
