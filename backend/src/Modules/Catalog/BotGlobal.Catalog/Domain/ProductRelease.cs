namespace BotGlobal.Catalog.Domain;

public sealed class ProductRelease
{
    private ProductRelease()
    {
    }

    public ProductRelease(
        Guid id,
        Guid productId,
        string version,
        PublicationStatus publicationStatus,
        int sortOrder,
        DateTimeOffset? releasedAtUtc = null,
        string? notesEn = null,
        string? notesAr = null)
    {
        if (id == Guid.Empty || productId == Guid.Empty)
        {
            throw new CatalogDomainException("Release and product identifiers are required.");
        }

        if (!Enum.IsDefined(publicationStatus))
        {
            throw new CatalogDomainException("Release publication status is invalid.");
        }

        if (string.IsNullOrWhiteSpace(version) || version.Trim().Length > 64)
        {
            throw new CatalogDomainException("Release version is required and cannot exceed 64 characters.");
        }

        if (sortOrder < 0)
        {
            throw new CatalogDomainException("Release sort order cannot be negative.");
        }

        Id = id;
        ProductId = productId;
        Version = version.Trim();
        PublicationStatus = publicationStatus;
        ReleasedAtUtc = releasedAtUtc;
        NotesEn = Optional(notesEn);
        NotesAr = Optional(notesAr);
        SortOrder = sortOrder;
    }

    public Guid Id { get; private set; }
    public Guid ProductId { get; private set; }
    public string Version { get; private set; } = null!;
    public PublicationStatus PublicationStatus { get; private set; }
    public DateTimeOffset? ReleasedAtUtc { get; private set; }
    public string? NotesEn { get; private set; }
    public string? NotesAr { get; private set; }
    public int SortOrder { get; private set; }

    private static string? Optional(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
