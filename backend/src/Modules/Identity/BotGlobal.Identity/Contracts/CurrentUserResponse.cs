namespace BotGlobal.Identity.Contracts;

public sealed record CurrentUserResponse(
    Guid Id,
    string UserName,
    string Email,
    string DisplayName,
    IReadOnlyCollection<string> Roles);
