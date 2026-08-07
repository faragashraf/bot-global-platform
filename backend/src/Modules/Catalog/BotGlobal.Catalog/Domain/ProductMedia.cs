namespace BotGlobal.Catalog.Domain;

public sealed class ProductMedia
{
    private ProductMedia()
    {
    }

    public ProductMedia(
        Guid id,
        Guid productId,
        ProductMediaKind kind,
        string storageProvider,
        string storageKey,
        string contentType,
        int sortOrder,
        long? byteLength = null,
        int? width = null,
        int? height = null,
        string? altTextEn = null,
        string? altTextAr = null)
    {
        if (id == Guid.Empty || productId == Guid.Empty)
        {
            throw new CatalogDomainException("Media and product identifiers are required.");
        }

        if (!Enum.IsDefined(kind))
        {
            throw new CatalogDomainException("Media kind is invalid.");
        }

        if (sortOrder < 0 || byteLength < 0 || width <= 0 || height <= 0)
        {
            throw new CatalogDomainException("Media dimensions, length, and sort order must be valid non-negative values.");
        }

        Id = id;
        ProductId = productId;
        Kind = kind;
        StorageProvider = Required(storageProvider, nameof(storageProvider), 40);
        StorageKey = Required(storageKey, nameof(storageKey), 500);
        ContentType = Required(contentType, nameof(contentType), 100);
        ByteLength = byteLength;
        Width = width;
        Height = height;
        AltTextEn = Optional(altTextEn, nameof(altTextEn), 300);
        AltTextAr = Optional(altTextAr, nameof(altTextAr), 300);
        SortOrder = sortOrder;
    }

    public Guid Id { get; private set; }
    public Guid ProductId { get; private set; }
    public ProductMediaKind Kind { get; private set; }
    public string StorageProvider { get; private set; } = null!;
    public string StorageKey { get; private set; } = null!;
    public string ContentType { get; private set; } = null!;
    public long? ByteLength { get; private set; }
    public int? Width { get; private set; }
    public int? Height { get; private set; }
    public string? AltTextEn { get; private set; }
    public string? AltTextAr { get; private set; }
    public int SortOrder { get; private set; }

    private static string Required(string value, string parameterName, int maxLength)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new CatalogDomainException($"{parameterName} is required.");
        }

        var normalized = value.Trim();
        return normalized.Length <= maxLength
            ? normalized
            : throw new CatalogDomainException($"{parameterName} cannot exceed {maxLength} characters.");
    }

    private static string? Optional(string? value, string parameterName, int maxLength)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var normalized = value.Trim();
        return normalized.Length <= maxLength
            ? normalized
            : throw new CatalogDomainException($"{parameterName} cannot exceed {maxLength} characters.");
    }
}
