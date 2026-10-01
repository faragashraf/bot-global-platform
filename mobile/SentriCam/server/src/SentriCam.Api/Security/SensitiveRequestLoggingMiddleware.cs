using Microsoft.AspNetCore.WebUtilities;

namespace SentriCam.Api.Security;

public sealed class SensitiveRequestLoggingMiddleware(RequestDelegate next)
{
    public const string AccessTokenItemKey = "SentriCam.SignalR.AccessToken";
    public const string RedactedValue = "[REDACTED]";

    public async Task InvokeAsync(HttpContext context)
    {
        ArgumentNullException.ThrowIfNull(context);

        if (IsSignalRHubRequest(context.Request.Path))
        {
            RedactAccessToken(context);
        }

        await next(context);
    }

    private static bool IsSignalRHubRequest(PathString path) =>
        path.StartsWithSegments("/hubs/device")
        || path.StartsWithSegments("/hubs/monitoring");

    private static void RedactAccessToken(HttpContext context)
    {
        var query = QueryHelpers.ParseQuery(context.Request.QueryString.Value);
        if (!query.TryGetValue("access_token", out var accessTokens))
        {
            return;
        }

        var accessToken = accessTokens.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));
        if (accessToken is null)
        {
            return;
        }

        context.Items[AccessTokenItemKey] = accessToken;
        context.Request.QueryString = QueryString.Create(
            query.SelectMany(parameter => parameter.Value.Select(value =>
                new KeyValuePair<string, string?>(
                    parameter.Key,
                    string.Equals(parameter.Key, "access_token", StringComparison.OrdinalIgnoreCase)
                        ? RedactedValue
                        : value))));
    }
}
