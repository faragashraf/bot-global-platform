namespace BotGlobal.Catalog.Domain;

public sealed class ProductLink
{
    private ProductLink()
    {
    }

    public ProductLink(
        Guid id,
        Guid productId,
        ProductLinkType type,
        string url,
        int sortOrder,
        string? labelEn = null,
        string? labelAr = null)
    {
        if (id == Guid.Empty || productId == Guid.Empty)
        {
            throw new CatalogDomainException("Link and product identifiers are required.");
        }

        if (!Enum.IsDefined(type))
        {
            throw new CatalogDomainException("Link type is invalid.");
        }

        if (!Uri.TryCreate(url, UriKind.Absolute, out var parsedUrl) || parsedUrl.Scheme is not ("http" or "https"))
        {
            throw new CatalogDomainException("Link URL must be an absolute HTTP or HTTPS URL.");
        }

        if (parsedUrl.AbsoluteUri.Length > 2048)
        {
            throw new CatalogDomainException("Link URL cannot exceed 2048 characters.");
        }

        if (sortOrder < 0)
        {
            throw new CatalogDomainException("Link sort order cannot be negative.");
        }

        Id = id;
        ProductId = productId;
        Type = type;
        Url = parsedUrl.AbsoluteUri;
        LabelEn = Optional(labelEn, nameof(labelEn));
        LabelAr = Optional(labelAr, nameof(labelAr));
        SortOrder = sortOrder;
    }

    public Guid Id { get; private set; }
    public Guid ProductId { get; private set; }
    public ProductLinkType Type { get; private set; }
    public string Url { get; private set; } = null!;
    public string? LabelEn { get; private set; }
    public string? LabelAr { get; private set; }
    public int SortOrder { get; private set; }

    private static string? Optional(string? value, string parameterName)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var normalized = value.Trim();
        return normalized.Length <= 200
            ? normalized
            : throw new CatalogDomainException($"{parameterName} cannot exceed 200 characters.");
    }
}
