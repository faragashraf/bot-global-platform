namespace SentriCam.BuildingBlocks.Authentication;

public static class AuthenticationClaimNames
{
    public const string TokenType = "sentricam_token_type";
    public const string DeviceId = "sentricam_device_id";
    public const string InstallationId = "sentricam_installation_id";
    public const string RegistrationId = "sentricam_registration_id";
}

public static class AuthenticationTokenTypes
{
    public const string Device = "device";
    public const string Operator = "operator";
}

public static class AuthorizationPolicies
{
    public const string Device = "SentriCam.Device";
    public const string DeviceControl = "SentriCam.DeviceControl";
    public const string DeviceAdministration = "SentriCam.DeviceAdministration";
}

public static class AuthorizationRoles
{
    public const string DeviceOperator = "DeviceOperator";
    public const string HubAdministrator = "HubAdministrator";
}

public static class SecurityRateLimitPolicies
{
    public const string DeviceRegistration = "SentriCam.DeviceRegistration";
    public const string SignalRConnections = "SentriCam.SignalRConnections";
}
