using BotGlobal.Communication.Domain.Conversations;
using BotGlobal.Communication.Domain.Identity;

namespace BotGlobal.UnitTests.Communication;

public sealed class ExternalUserIdentifierTests
{
    [Fact]
    public void Corporate_nvarchar20_identifier_is_supported()
    {
        const string id = "EMP00000000000000001";
        Assert.Equal(id, ExternalUserId.Normalize(id));
    }

    [Fact]
    public void Guid_formatted_identifier_is_supported_as_text()
    {
        var id = Guid.NewGuid().ToString();
        Assert.Equal(id, ExternalUserId.Normalize(id));
    }

    [Fact]
    public void Normalization_trims_but_preserves_case()
    {
        Assert.Equal(
            "AbC001",
            ExternalUserId.Normalize("  AbC001  "));
    }

    [Fact]
    public void Direct_key_is_stable_and_case_preserving()
    {
        var first = DirectConversationKey.Create(
            "ABC001",
            "abc002");

        var second = DirectConversationKey.Create(
            "abc002",
            "ABC001");

        Assert.Equal(first, second);
        Assert.Contains("ABC001", first);
        Assert.Contains("abc002", first);
    }

    [Fact]
    public void Values_longer_than_128_are_rejected()
    {
        Assert.Throws<ArgumentException>(
            () => ExternalUserId.Normalize(
                new string(
                    'x',
                    ExternalUserId.MaxLength + 1)));
    }
}
