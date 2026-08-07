namespace BotGlobal.Catalog.Domain;

public sealed class ProductLocalization
{
    private ProductLocalization()
    {
    }

    internal ProductLocalization(
        Guid productId,
        string language,
        string name,
        string shortDescription,
        string description,
        string? displayStatus,
        IEnumerable<string> platforms,
        IEnumerable<string> technologies)
    {
        ProductId = productId;
        Language = ValidateLanguage(language);
        ReplaceContent(name, shortDescription, description, displayStatus, platforms, technologies);
    }

    public Guid ProductId { get; private set; }

    public string Language { get; private set; } = null!;

    public string Name { get; private set; } = null!;

    public string ShortDescription { get; private set; } = null!;

    public string Description { get; private set; } = null!;

    public string? DisplayStatus { get; private set; }

    public IReadOnlyList<string> Platforms { get; private set; } = [];

    public IReadOnlyList<string> Technologies { get; private set; } = [];

    internal void ReplaceContent(
        string name,
        string shortDescription,
        string description,
        string? displayStatus,
        IEnumerable<string> platforms,
        IEnumerable<string> technologies)
    {
        Name = Required(name, nameof(name), 200);
        ShortDescription = Required(shortDescription, nameof(shortDescription), 600);
        Description = Required(description, nameof(description));
        DisplayStatus = Optional(displayStatus, nameof(displayStatus), 150);

        Platforms = NormalizeCollection(platforms, nameof(platforms));
        Technologies = NormalizeCollection(technologies, nameof(technologies));
    }

    internal static string ValidateLanguage(string language)
    {
        if (language is not ("en" or "ar"))
        {
            throw new CatalogDomainException("Catalog language must be 'en' or 'ar'.");
        }

        return language;
    }

    private static string Required(string value, string parameterName, int? maxLength = null)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new CatalogDomainException($"{parameterName} is required.");
        }

        var normalized = value.Trim();
        if (maxLength.HasValue && normalized.Length > maxLength.Value)
        {
            throw new CatalogDomainException($"{parameterName} cannot exceed {maxLength.Value} characters.");
        }

        return normalized;
    }

    private static string? Optional(string? value, string parameterName, int maxLength)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var normalized = value.Trim();
        if (normalized.Length > maxLength)
        {
            throw new CatalogDomainException($"{parameterName} cannot exceed {maxLength} characters.");
        }

        return normalized;
    }

    private static IReadOnlyList<string> NormalizeCollection(IEnumerable<string> values, string parameterName)
    {
        ArgumentNullException.ThrowIfNull(values, parameterName);

        return values
            .Select(value => Required(value, parameterName))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }
}
