using BotGlobal.Communication.Contracts.Common;
using BotGlobal.Communication.Contracts.Messaging;

namespace BotGlobal.UnitTests.Communication;

public sealed class CommunicationContractTests
{
    [Fact]
    public void ConversationGroup_UsesStableNamespacedName()
    {
        var group = CommunicationIds.ConversationGroup(
            " conversation-123 ");

        Assert.Equal(
            "conversation:conversation-123",
            group);
    }

    [Fact]
    public void ConversationGroup_RejectsEmptyIdentifier()
    {
        Assert.Throws<ArgumentException>(
            () => CommunicationIds.ConversationGroup(" "));
    }

    [Theory]
    [InlineData(MessageKind.Text)]
    [InlineData(MessageKind.Link)]
    [InlineData(MessageKind.Image)]
    [InlineData(MessageKind.Video)]
    [InlineData(MessageKind.Voice)]
    [InlineData(MessageKind.File)]
    public void MessageKind_ContainsPlannedTransportKinds(
        MessageKind kind)
    {
        Assert.True(Enum.IsDefined(kind));
    }
}
