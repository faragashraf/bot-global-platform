using BotGlobal.Catalog.Contracts.Admin;

namespace BotGlobal.Catalog.Application.Admin;

public enum AdminCatalogCommandFailureKind
{
    InvalidRequest,
    NotFound,
    Conflict,
    Validation
}

public sealed record AdminCatalogCommandFailure(
    AdminCatalogCommandFailureKind Kind,
    string Detail);

public sealed record AdminCatalogCommandResult(
    AdminCatalogProductDetailDto? Product,
    AdminCatalogCommandFailure? Failure)
{
    public static AdminCatalogCommandResult Success(AdminCatalogProductDetailDto product) =>
        new(product, null);

    public static AdminCatalogCommandResult Failed(
        AdminCatalogCommandFailureKind kind,
        string detail) =>
        new(null, new AdminCatalogCommandFailure(kind, detail));
}
