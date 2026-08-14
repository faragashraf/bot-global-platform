namespace BotGlobal.PlatformClients.Authentication;

public static class PlatformClientAuthenticationDefaults
{
    public const string Scheme = "PlatformClient";
    public const string ClientKeyHeader = "X-Platform-Client-Key";
    public const string ClientSecretHeader = "X-Platform-Client-Secret";

    public const string ClientIdClaim = "platform_client_id";
    public const string ClientKeyClaim = "platform_client_key";
    public const string CapabilityClaim = "platform_client_capability";
}
