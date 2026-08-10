using Microsoft.AspNetCore.Identity;

namespace BotGlobal.Identity.Domain;

public sealed class ApplicationUser : IdentityUser<Guid>
{
    public string DisplayName { get; private set; } = string.Empty;
    public bool IsActive { get; private set; } = true;
    public DateTimeOffset CreatedAtUtc { get; private set; } = DateTimeOffset.UtcNow;

    private ApplicationUser()
    {
    }

    public ApplicationUser(
        Guid id,
        string userName,
        string email,
        string displayName)
    {
        if (id == Guid.Empty)
        {
            throw new ArgumentException(
                "User id is required.",
                nameof(id));
        }

        Id = id;
        UserName = Require(
            userName,
            nameof(userName),
            256);

        Email = Require(
            email,
            nameof(email),
            256);

        DisplayName = Require(
            displayName,
            nameof(displayName),
            200);

        IsActive = true;
        CreatedAtUtc = DateTimeOffset.UtcNow;
    }

    public void SetDisplayName(string displayName)
    {
        DisplayName = Require(
            displayName,
            nameof(displayName),
            200);
    }

    public void Activate() => IsActive = true;

    public void Deactivate() => IsActive = false;

    private static string Require(
        string value,
        string name,
        int maxLength)
    {
        var normalized = value?.Trim();

        if (string.IsNullOrWhiteSpace(normalized))
        {
            throw new ArgumentException(
                $"{name} is required.",
                name);
        }

        if (normalized.Length > maxLength)
        {
            throw new ArgumentException(
                $"{name} exceeds {maxLength} characters.",
                name);
        }

        return normalized;
    }
}
