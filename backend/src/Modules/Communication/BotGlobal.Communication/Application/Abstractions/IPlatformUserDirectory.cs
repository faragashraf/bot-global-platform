namespace BotGlobal.Communication.Application.Abstractions;

public sealed record PlatformUserDirectoryEntry(
    string UserId,
    string? DisplayName,
    bool IsActive);

public interface IPlatformUserDirectory
{
    Task<PlatformUserDirectoryEntry?> FindByIdAsync(
        string userId,
        CancellationToken cancellationToken = default);

    Task<bool> IsActiveAsync(
        string userId,
        CancellationToken cancellationToken = default);
}
