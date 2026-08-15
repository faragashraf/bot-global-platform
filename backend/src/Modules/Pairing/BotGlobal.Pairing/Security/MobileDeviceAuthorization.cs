using Microsoft.Extensions.Primitives;

namespace BotGlobal.Pairing.Security;

public static class MobileDeviceAuthorization
{
    public const string Scheme = "Device";

    public static bool TryReadCredential(
        StringValues authorizationValues,
        out string credential)
    {
        credential = string.Empty;

        var value =
            authorizationValues.FirstOrDefault();

        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }

        var prefix = Scheme + " ";

        if (!value.StartsWith(
                prefix,
                StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var parsed =
            value[prefix.Length..].Trim();

        if (string.IsNullOrWhiteSpace(parsed))
        {
            return false;
        }

        credential = parsed;
        return true;
    }
}
