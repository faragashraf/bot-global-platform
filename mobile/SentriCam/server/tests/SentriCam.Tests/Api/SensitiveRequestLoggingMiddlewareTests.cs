using System.Text.Json;
using Microsoft.AspNetCore.Http;
using SentriCam.Api.Security;

namespace SentriCam.Tests.Api;

public sealed class SensitiveRequestLoggingMiddlewareTests
{
    [Fact]
    public async Task SignalRAccessTokenIsPreservedForAuthenticationAndRedactedFromRequestMetadata()
    {
        const string token = "eyJ-sensitive-device-token";
        var context = new DefaultHttpContext();
        context.Request.Path = "/hubs/device";
        context.Request.QueryString = new QueryString($"?id=transport-id&access_token={token}");
        var middleware = new SensitiveRequestLoggingMiddleware(_ => Task.CompletedTask);

        await middleware.InvokeAsync(context);

        Assert.Equal(token, context.Items[SensitiveRequestLoggingMiddleware.AccessTokenItemKey]);
        Assert.DoesNotContain(token, context.Request.QueryString.Value, StringComparison.Ordinal);
        Assert.Contains(
            Uri.EscapeDataString(SensitiveRequestLoggingMiddleware.RedactedValue),
            context.Request.QueryString.Value,
            StringComparison.Ordinal);
        Assert.Equal("transport-id", context.Request.Query["id"]);
    }

    [Theory]
    [InlineData("appsettings.json")]
    [InlineData("appsettings.Development.json")]
    public void RequestUrlLoggingCategoriesAreSuppressed(string fileName)
    {
        var path = Path.Combine(ServerRoot(), "src", "SentriCam.Api", fileName);
        using var document = JsonDocument.Parse(File.ReadAllText(path));
        var levels = document.RootElement.GetProperty("Logging").GetProperty("LogLevel");

        Assert.Equal("Warning", levels.GetProperty("Microsoft.AspNetCore.Hosting.Diagnostics").GetString());
        Assert.Equal("Warning", levels.GetProperty("Microsoft.AspNetCore.Http.Connections").GetString());
    }

    [Fact]
    public void ServerDoesNotEnableRequestHeaderOrBodyLogging()
    {
        var program = File.ReadAllText(Path.Combine(ServerRoot(), "src", "SentriCam.Api", "Program.cs"));

        Assert.DoesNotContain("AddHttpLogging", program, StringComparison.Ordinal);
        Assert.DoesNotContain("UseHttpLogging", program, StringComparison.Ordinal);
    }

    private static string ServerRoot()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory);
             directory is not null;
             directory = directory.Parent)
        {
            if (File.Exists(Path.Combine(directory.FullName, "SentriCam.Server.sln")))
            {
                return directory.FullName;
            }
        }

        throw new DirectoryNotFoundException("Could not locate the SentriCam server root.");
    }
}
