using Microsoft.AspNetCore.Http;

namespace BotGlobal.Calling.Realtime;

internal static class NqrbGuestCallLink
{
    private const string LegacyPagePath = "/nqrb/guest-call";
    private const string PublicPagePath = "/guest-call";

    public static bool IsValidPublicPageUrl(string? publicPageUrl)
    {
        if (string.IsNullOrWhiteSpace(publicPageUrl)) return true;
        return Uri.TryCreate(publicPageUrl, UriKind.Absolute, out var uri)
               && uri.Scheme == Uri.UriSchemeHttps
               && uri.AbsolutePath == PublicPagePath
               && uri.UserInfo.Length == 0
               && uri.Query.Length == 0
               && uri.Fragment.Length == 0;
    }

    public static string Build(HttpRequest request, string capability, string? publicPageUrl)
    {
        var pageUrl = string.IsNullOrWhiteSpace(publicPageUrl)
            ? $"{request.Scheme}://{request.Host}{request.PathBase}{LegacyPagePath}"
            : publicPageUrl;
        return $"{pageUrl}#{Uri.EscapeDataString(capability)}";
    }
}
