using System.Net;
using System.Net.Http.Json;
using BotGlobal.Catalog.Application;
using BotGlobal.Catalog.Contracts;
using BotGlobal.Catalog.Domain;
using BotGlobal.Catalog.Endpoints;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Catalog;

public sealed class CatalogEndpointsTests
{
    [Theory]
    [InlineData("/api/catalog/products?category=other", "category")]
    [InlineData("/api/catalog/products?featured=yes", "featured")]
    [InlineData("/api/catalog/products/other/product", "category")]
    public async Task Invalid_category_or_query_returns_problem_details(string url, string parameter)
    {
        await using var host = await CreateHostAsync(new StubPublicCatalogQueries());

        var response = await host.Client.GetAsync(url);
        var problem = await response.Content.ReadFromJsonAsync<HttpValidationProblem>();

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.NotNull(problem);
        Assert.Equal("Invalid catalog query", problem.Title);
        Assert.Equal(parameter, problem.Parameter);
    }

    [Fact]
    public async Task Missing_or_hidden_product_returns_not_found_problem_details()
    {
        await using var host = await CreateHostAsync(new StubPublicCatalogQueries());

        var response = await host.Client.GetAsync("/api/catalog/products/app/missing");
        var problem = await response.Content.ReadFromJsonAsync<HttpValidationProblem>();

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal("Catalog product not found", problem?.Title);
    }

    [Fact]
    public async Task Collection_endpoint_passes_valid_combined_filters_to_query_service()
    {
        var queries = new StubPublicCatalogQueries();
        await using var host = await CreateHostAsync(queries);

        var response = await host.Client.GetAsync("/api/catalog/products?category=app&featured=true");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(ProductCategory.App, queries.Category);
        Assert.True(queries.Featured);
    }

    [Fact]
    public async Task Catalog_routes_are_registered_exactly_once()
    {
        await using var host = await CreateHostAsync(new StubPublicCatalogQueries());
        var patterns = host.App.Services
            .GetServices<EndpointDataSource>()
            .SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .Select(endpoint => endpoint.RoutePattern.RawText)
            .ToArray();

        Assert.Single(patterns, pattern => pattern == "/api/catalog/products");
        Assert.Single(patterns, pattern => pattern == "/api/catalog/products/{category}/{slug}");
    }

    private static async Task<TestHost> CreateHostAsync(IPublicCatalogQueries queries)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddProblemDetails();
        builder.Services.AddSingleton(queries);

        var app = builder.Build();
        app.MapCatalogEndpoints();
        await app.StartAsync();
        return new TestHost(app, app.GetTestClient());
    }

    private sealed class StubPublicCatalogQueries : IPublicCatalogQueries
    {
        public ProductCategory? Category { get; private set; }
        public bool? Featured { get; private set; }

        public Task<IReadOnlyList<PublicCatalogProductDto>> GetProductsAsync(
            ProductCategory? category,
            bool? featured,
            CancellationToken cancellationToken = default)
        {
            Category = category;
            Featured = featured;
            return Task.FromResult<IReadOnlyList<PublicCatalogProductDto>>([]);
        }

        public Task<PublicCatalogProductDto?> GetProductAsync(
            ProductCategory category,
            string slug,
            CancellationToken cancellationToken = default) =>
            Task.FromResult<PublicCatalogProductDto?>(null);
    }

    private sealed record HttpValidationProblem(string? Title, string? Parameter);

    private sealed class TestHost(WebApplication app, HttpClient client) : IAsyncDisposable
    {
        public WebApplication App { get; } = app;
        public HttpClient Client { get; } = client;

        public async ValueTask DisposeAsync()
        {
            Client.Dispose();
            await App.DisposeAsync();
        }
    }
}
