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
    public async Task Products_route_is_get_only_and_requires_administrator_policy()
    {
        await using var host = await CreateHostAsync(new StubAdminCatalogQueries());
        var endpoint = host.App.Services
            .GetServices<EndpointDataSource>()
            .SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .Single(candidate => candidate.RoutePattern.RawText == "/api/admin/catalog/products");

        var methods = endpoint.Metadata.GetRequiredMetadata<HttpMethodMetadata>();
        var authorization = endpoint.Metadata.GetOrderedMetadata<IAuthorizeData>();

        Assert.Equal([HttpMethods.Get], methods.HttpMethods);
        Assert.Contains(authorization, data => data.Policy == "Administrator");
    }

    private static async Task<TestHost> CreateHostAsync(IAdminCatalogQueryService queries)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddSingleton(queries);

        var app = builder.Build();
        app.MapAdminCatalogEndpoints();
        await app.StartAsync();
        return new TestHost(app, app.GetTestClient());
    }

    private sealed class StubAdminCatalogQueries : IAdminCatalogQueryService
    {
        public Task<AdminCatalogProductsResponse> GetProductsAsync(
            string? search,
            ProductCategory? category,
            PublicationStatus? status,
            bool? featured,
            CancellationToken cancellationToken = default) =>
            Task.FromResult(new AdminCatalogProductsResponse([], 0));
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
