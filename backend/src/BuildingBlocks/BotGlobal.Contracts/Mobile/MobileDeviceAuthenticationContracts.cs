namespace BotGlobal.Contracts.Mobile;

public static class MobileDeviceAuthenticationDefaults
{
    public const string Scheme = "MobileDevice";

    public const string DeviceIdClaim =
        "mobile_device_id";

    public const string PlatformClientIdClaim =
        "platform_client_id";

    public const string ExternalSubjectIdClaim =
        "external_subject_id";
}

public static class MobileNotificationRealtimeContract
{
    public const string HubPath =
        "/hubs/mobile-notifications";

    public const string ReceiveEvent =
        "MobileNotificationReceived";

    public static string DeviceGroup(Guid deviceId) =>
        $"mobile-device:{deviceId:N}";
}
