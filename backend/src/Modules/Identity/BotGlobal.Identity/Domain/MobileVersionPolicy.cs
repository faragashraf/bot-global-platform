namespace BotGlobal.Identity.Domain;

public sealed class MobileVersionPolicy
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string ApplicationKey { get; set; } = string.Empty;
    public string Platform { get; set; } = string.Empty;
    public string LatestVersion { get; set; } = string.Empty;
    public string MinimumSupportedVersion { get; set; } = string.Empty;
    public string? Message { get; set; }
    public string? StoreDestination { get; set; }
    public bool IsActive { get; set; } = true;
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
    public Guid? UpdatedByUserId { get; set; }
    public string? UpdatedByDisplayName { get; set; }
}
