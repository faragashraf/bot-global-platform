using BotGlobal.Catalog.Application.Admin;
using BotGlobal.Catalog.Contracts.Admin;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Endpoints;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Catalog;

public sealed class AdminCatalogEndpointsTests
{
    [Fact]
    public async Task Admin_catalog_routes_require_administrator_policy()
    {
        await using var host = await CreateHostAsync(
            new StubAdminCatalogQueries(),
            new StubAdminCatalogCommands());
        var endpoints = host.App.Services
            .GetServices<EndpointDataSource>()
            .SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .ToArray();

        AssertProtectedEndpoint(endpoints, "/api/admin/catalog/products", HttpMethods.Get);
        AssertProtectedEndpoint(endpoints, "/api/admin/catalog/products", HttpMethods.Post);
        AssertProtectedEndpoint(endpoints, "/api/admin/catalog/products/{id:guid}", HttpMethods.Get);
        AssertProtectedEndpoint(endpoints, "/api/admin/catalog/products/{id:guid}", HttpMethods.Put);
    }

    [Fact]
    public void Write_contracts_do_not_accept_lifecycle_fields()
    {
        var lifecycleProperties = new[] { "PublicationStatus", "PublishedAtUtc" };

        Assert.DoesNotContain(
            typeof(CreateCatalogProductRequest).GetProperties(),
            property => lifecycleProperties.Contains(property.Name));
        Assert.DoesNotContain(
            typeof(UpdateCatalogProductRequest).GetProperties(),
            property => lifecycleProperties.Contains(property.Name));
    }

    private static void AssertProtectedEndpoint(
        IReadOnlyCollection<RouteEndpoint> endpoints,
        string pattern,
        string method)
    {
        var endpoint = Assert.Single(endpoints, candidate =>
            candidate.RoutePattern.RawText == pattern &&
            candidate.Metadata.GetRequiredMetadata<HttpMethodMetadata>().HttpMethods.Contains(method));
        var authorization = endpoint.Metadata.GetOrderedMetadata<IAuthorizeData>();

        Assert.Contains(authorization, data => data.Policy == "Administrator");
    }

    private static async Task<TestHost> CreateHostAsync(
        IAdminCatalogQueryService queries,
        IAdminCatalogCommandService commands)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddSingleton(queries);
        builder.Services.AddSingleton(commands);

        var app = builder.Build();
        app.MapAdminCatalogEndpoints();
        await app.StartAsync();
        return new TestHost(app, app.GetTestClient());
    }

    private sealed class StubAdminCatalogQueries : IAdminCatalogQueryService
    {
        public Task<AdminCatalogProductDetailDto?> GetProductAsync(
            Guid id,
            CancellationToken cancellationToken = default) =>
            Task.FromResult<AdminCatalogProductDetailDto?>(null);

        public Task<AdminCatalogProductsResponse> GetProductsAsync(
            string? search,
            ProductCategory? category,
            PublicationStatus? status,
            bool? featured,
            CancellationToken cancellationToken = default) =>
            Task.FromResult(new AdminCatalogProductsResponse([], 0));
    }

    private sealed class StubAdminCatalogCommands : IAdminCatalogCommandService
    {
        public Task<AdminCatalogCommandResult> CreateAsync(
            CreateCatalogProductRequest? request,
            CancellationToken cancellationToken = default) =>
            Task.FromResult(AdminCatalogCommandResult.Failed(
                AdminCatalogCommandFailureKind.InvalidRequest,
                "Not used by route metadata tests."));

        public Task<AdminCatalogCommandResult> UpdateAsync(
            Guid id,
            UpdateCatalogProductRequest? request,
            CancellationToken cancellationToken = default) =>
            Task.FromResult(AdminCatalogCommandResult.Failed(
                AdminCatalogCommandFailureKind.InvalidRequest,
                "Not used by route metadata tests."));
    }

    private sealed class TestHost(WebApplication app, HttpClient client) : IAsyncDisposable
    {
        public WebApplication App { get; } = app;

        public async ValueTask DisposeAsync()
        {
            client.Dispose();
            await App.DisposeAsync();
        }
    }
}
