namespace BotGlobal.Communication.Application.MobileNotifications.Push;

internal static class PushProviderNames
{
    public const string FirebaseCloudMessaging = "fcm";
    public const string ApplePushNotificationService = "apns";
}

internal sealed class ApplicationPushProviderOptions
{
    public const string SectionName =
        "Notifications:PushProviders";

    public int DefaultTimeToLiveDays { get; init; } = 3;

    public List<ApplicationPushProviderConfiguration> Providers {
        get;
        init;
    } = [];
}

internal sealed class ApplicationPushProviderConfiguration
{
    public Guid ApplicationId { get; init; }

    public string Provider { get; init; } = string.Empty;

    public bool Enabled { get; init; }

    public string ConfigurationReference { get; init; } = string.Empty;

    public string? FirebaseProjectId { get; init; }

    public string? AndroidPackageName { get; init; }

    public string? AppleBundleId { get; init; }
}
