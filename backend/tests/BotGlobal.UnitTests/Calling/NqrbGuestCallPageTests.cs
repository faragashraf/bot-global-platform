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
        Assert.Contains("حمّل نقرب من Google Play", html);
        Assert.Contains("Get Nqrb on Google Play", html);
        Assert.DoesNotContain("Coming soon", html);
        Assert.DoesNotContain("قريبًا على Google Play", html);
        Assert.Contains("id=\"join\"", html);
        Assert.Contains("id=\"mute\"", html);
        Assert.Contains("class=\"panel-body unavailable\"", html);
        Assert.Contains("Your host declined the call", html);
        Assert.Contains("href=\"https://play.google.com/store/apps/details?id=com.botglobal.nqrb\"", html);
        Assert.Contains("rel=\"noopener noreferrer\"", html);
        Assert.Contains("الكلام الحلو <em>يبدأ بصوتك.</em>", html);
        Assert.Contains("id=\"hostName\"", html);
        Assert.Contains("$('hostName').textContent = hostDisplayName", html);
    }
}
