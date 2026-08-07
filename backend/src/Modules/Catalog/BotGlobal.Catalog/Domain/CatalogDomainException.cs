namespace BotGlobal.Catalog.Domain;

public sealed class CatalogDomainException(string message) : InvalidOperationException(message);
