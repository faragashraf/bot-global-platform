using System.Text.RegularExpressions;
using BotGlobal.Pairing.Contracts;
using BotGlobal.Pairing.Domain;

namespace BotGlobal.Pairing.Application;

internal static partial class MobileDeviceInputNormalizer
{
    public static CompletedMobileDevice Normalize(
        ClaimPairingDeviceRequest device)
    {
        ArgumentNullException.ThrowIfNull(device);

        var platform = NormalizeRequiredValue(
            device.Platform,
            nameof(device.Platform),
            PairingChallenge.MobilePlatformMaxLength,
            PlatformPattern());

        if (!string.Equals(
                platform,
                "android",
                StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException(
                "Device platform is not supported.",
                nameof(device.Platform));
        }

        return new CompletedMobileDevice(
            platform.ToLowerInvariant(),
            NormalizeRequiredValue(
                device.InstallationId,
                nameof(device.InstallationId),
                PairingChallenge.MobileInstallationIdMaxLength,
                SafeIdentifierPattern()),
            NormalizeOptionalValue(
                device.DeviceName,
                nameof(device.DeviceName),
                PairingChallenge.MobileDeviceNameMaxLength),
            NormalizeOptionalValue(
                device.AppVersion,
                nameof(device.AppVersion),
                PairingChallenge.MobileAppVersionMaxLength,
                AppVersionPattern()));
    }

    private static string NormalizeRequiredValue(
        string value,
        string parameterName,
        int maxLength,
        Regex pattern)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException(
                "Required value is missing.",
                parameterName);
        }

        var normalized = value.Trim();

        if (normalized.Length > maxLength || !pattern.IsMatch(normalized))
        {
            throw new ArgumentException(
                "Value is malformed.",
                parameterName);
        }

        return normalized;
    }

    private static string? NormalizeOptionalValue(
        string? value,
        string parameterName,
        int maxLength,
        Regex? pattern = null)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var normalized = value.Trim();

        if (normalized.Length > maxLength
            || (pattern is not null && !pattern.IsMatch(normalized)))
        {
            throw new ArgumentException(
                "Value is malformed.",
                parameterName);
        }

        return normalized;
    }

    [GeneratedRegex("^[A-Za-z][A-Za-z0-9_-]*$", RegexOptions.CultureInvariant)]
    private static partial Regex PlatformPattern();

    [GeneratedRegex("^[A-Za-z0-9._:-]+$", RegexOptions.CultureInvariant)]
    private static partial Regex SafeIdentifierPattern();

    [GeneratedRegex("^[A-Za-z0-9._+:-]+$", RegexOptions.CultureInvariant)]
    private static partial Regex AppVersionPattern();
}
