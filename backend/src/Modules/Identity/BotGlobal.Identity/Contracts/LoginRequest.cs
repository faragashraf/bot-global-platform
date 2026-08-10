namespace BotGlobal.Identity.Contracts;

public sealed record LoginRequest(
    string UserNameOrEmail,
    string Password,
    bool RememberMe = false);
