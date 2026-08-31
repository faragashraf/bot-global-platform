namespace SentriCam.Domain.Common;

internal static class DomainGuard
{
    public static string Required(string? value, int maxLength, string name)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new DomainValidationException($"{name} is required.");
        }

        var trimmed = value.Trim();
        if (trimmed.Length > maxLength)
        {
            throw new DomainValidationException($"{name} must not exceed {maxLength} characters.");
        }

        return trimmed;
    }

    public static string? Optional(string? value, int maxLength, string name)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        return Required(value, maxLength, name);
    }

    public static void Percentage(int? value, string name)
    {
        if (value is < 0 or > 100)
        {
            throw new DomainValidationException($"{name} must be between 0 and 100.");
        }
    }
}
