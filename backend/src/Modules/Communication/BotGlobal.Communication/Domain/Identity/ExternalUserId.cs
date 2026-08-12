namespace BotGlobal.Communication.Domain.Identity;

public static class ExternalUserId
{
    public const int MaxLength = 128;

    public static string Normalize(
        string userId,
        string? parameterName = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            userId,
            parameterName ?? nameof(userId));

        var normalized = userId.Trim();

        if (normalized.Length > MaxLength)
        {
            throw new ArgumentException(
                $"User identifier cannot exceed {MaxLength} characters.",
                parameterName ?? nameof(userId));
        }

        return normalized;
    }
}
