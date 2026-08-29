using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Realtime;
using Microsoft.AspNetCore.Authorization;

namespace BotGlobal.UnitTests.Games;

public sealed class GamesRealtimeContractTests
{
    [Fact]
    public void Hub_is_bound_to_mobile_session_and_family_games_policy()
    {
        var attribute = Assert.Single(
            typeof(GamesHub).GetCustomAttributes(typeof(AuthorizeAttribute), inherit: true)
                .Cast<AuthorizeAttribute>());

        Assert.Equal(ApplicationIdentityDefaults.Scheme, attribute.AuthenticationSchemes);
        Assert.Equal(ApplicationIdentityPolicies.For(BotGlobalApplications.FamilyGames), attribute.Policy);
    }

    [Fact]
    public void Hub_exposes_rejoin_and_authoritative_commands()
    {
        var methodNames = typeof(GamesHub).GetMethods().Select(x => x.Name).ToHashSet();
        Assert.Contains("Rejoin", methodNames);
        Assert.Contains("Ready", methodNames);
        Assert.Contains("Move", methodNames);
        Assert.Contains("RequestRematch", methodNames);
        Assert.Contains("AcceptRematch", methodNames);
        Assert.Contains("JoinVoiceRoom", methodNames);
        Assert.Contains("LeaveVoiceRoom", methodNames);
        Assert.Contains("VoiceOffer", methodNames);
        Assert.Contains("VoiceAnswer", methodNames);
        Assert.Contains("VoiceIceCandidate", methodNames);
        Assert.Contains("GetVoiceIceConfiguration", methodNames);
        Assert.Contains("RequestVoice", methodNames);
        Assert.Contains("AcceptVoice", methodNames);
        Assert.Contains("DeclineVoice", methodNames);
        Assert.Contains("CancelVoiceRequest", methodNames);
        Assert.Contains("EndVoice", methodNames);
    }
}
