using BotGlobal.Catalog.Application;

namespace BotGlobal.Catalog.Infrastructure;

internal sealed class NullMediaUrlResolver : IMediaUrlResolver
{
    public string? ResolvePublicUrl(string storageProvider, string storageKey) => null;
}
