using BotGlobal.Calling.Realtime;
using Microsoft.AspNetCore.Http;

namespace BotGlobal.UnitTests.Calling;

public sealed class NqrbGuestCallLinkTests
{
    [Fact]
    public void Production_link_uses_configured_frontend_host_instead_of_api_request_host()
    {
        var request = new DefaultHttpContext().Request;
        request.Scheme = "https";
        request.Host = new HostString("api.botglobalservice.com");

        Assert.True(NqrbGuestCallLink.IsValidPublicPageUrl("https://nqrb.botglobalservice.com/guest-call"));
        var link = NqrbGuestCallLink.Build(request, "guest capability", "https://nqrb.botglobalservice.com/guest-call");

        Assert.Equal("https://nqrb.botglobalservice.com/guest-call#guest%20capability", link);
    }

    [Fact]
    public void Local_link_keeps_request_path_base_for_existing_backend_routes()
    {
        var request = new DefaultHttpContext().Request;
        request.Scheme = "http";
        request.Host = new HostString("localhost:8080");
        request.PathBase = "/backend";

        var link = NqrbGuestCallLink.Build(request, "guest", null);

        Assert.Equal("http://localhost:8080/backend/nqrb/guest-call#guest", link);
    }

    [Theory]
    [InlineData("http://nqrb.botglobalservice.com/guest-call")]
    [InlineData("https://nqrb.botglobalservice.com/guest-call?redirect=elsewhere")]
    [InlineData("https://nqrb.botglobalservice.com/guest-call#old")]
    [InlineData("https://user:pass@nqrb.botglobalservice.com/guest-call")]
    [InlineData("https://nqrb.botglobalservice.com/other-page")]
    public void Unsafe_or_incorrect_public_page_urls_are_rejected(string url)
    {
        Assert.False(NqrbGuestCallLink.IsValidPublicPageUrl(url));
    }
}
