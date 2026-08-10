namespace BotGlobal.Identity.Infrastructure;

public sealed class BootstrapAdminOptions
{
    public const string SectionName =
        "Identity:BootstrapAdmin";

    public bool Enabled { get; init; }

    public string UserName { get; init; } =
        string.Empty;

    public string Email { get; init; } =
        string.Empty;

    public string DisplayName { get; init; } =
        string.Empty;
}
