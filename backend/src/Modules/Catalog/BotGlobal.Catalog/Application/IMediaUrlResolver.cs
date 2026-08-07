namespace BotGlobal.Catalog.Application;

public interface IMediaUrlResolver
{
    string? ResolvePublicUrl(string storageProvider, string storageKey);
}
