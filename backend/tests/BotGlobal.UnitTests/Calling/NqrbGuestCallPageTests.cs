using BotGlobal.Calling.Realtime;

namespace BotGlobal.UnitTests.Calling;

public sealed class NqrbGuestCallPageTests
{
    [Fact]
    public void Page_embeds_both_brands_and_truthful_store_message_without_external_assets()
    {
        var html = NqrbGuestCallPage.Html();

        Assert.Contains("class=\"app-mark\"", html);
        Assert.Contains("data:image/png;base64,", html);
        Assert.DoesNotContain("__BOT_GLOBAL_MARK__", html);
        Assert.Contains("قريبًا على Google Play", html);
        Assert.Contains("Coming soon to Google Play", html);
        Assert.Contains("id=\"join\"", html);
        Assert.Contains("id=\"mute\"", html);
        Assert.Contains("class=\"panel-body unavailable\"", html);
        Assert.Contains("Your host declined the call", html);
        Assert.DoesNotContain("href=\"https://play.google.com", html);
    }
}
