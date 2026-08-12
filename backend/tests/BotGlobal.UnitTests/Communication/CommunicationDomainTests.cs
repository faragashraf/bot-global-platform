using BotGlobal.Communication.Domain.Calls;
using BotGlobal.Communication.Domain.Conversations;
using BotGlobal.Communication.Domain.Messaging;
using BotGlobal.Communication.Domain.Preferences;

namespace BotGlobal.UnitTests.Communication;

public sealed class CommunicationDomainTests
{
    private static readonly DateTimeOffset Now =
        new(2026, 8, 13, 0, 0, 0, TimeSpan.Zero);

    [Fact]
    public void Direct_key_is_stable_regardless_of_user_order()
    {
        const string first = "USER-0001";
        const string second = "USER-0002";

        Assert.Equal(
            DirectConversationKey.Create(first, second),
            DirectConversationKey.Create(second, first));
    }

    [Fact]
    public void Direct_conversation_starts_with_exactly_two_members()
    {
        var conversation = Conversation.CreateDirect(
            "USER-0001",
            "USER-0002",
            Now);

        Assert.Equal(ConversationType.Direct, conversation.Type);
        Assert.Null(conversation.Title);
        Assert.NotNull(conversation.DirectKey);
        Assert.Equal(2, conversation.Participants.Count);
    }

    [Fact]
    public void Direct_conversation_rejects_third_participant()
    {
        var conversation = Conversation.CreateDirect(
            "USER-0001",
            "USER-0002",
            Now);

        Assert.Throws<InvalidOperationException>(
            () => conversation.AddParticipant(
                "USER-0003",
                Now));
    }

    [Fact]
    public void Group_creator_is_owner()
    {
        const string owner = "OWNER-0001";

        var conversation = Conversation.CreateGroup(
            owner,
            "Engineering",
            Now);

        var participant = Assert.Single(conversation.Participants);

        Assert.Equal(owner, participant.UserId);
        Assert.Equal(
            ConversationParticipantRole.Owner,
            participant.Role);
    }

    [Fact]
    public void Text_message_requires_non_empty_text()
    {
        Assert.Throws<ArgumentException>(
            () => Message.CreateText(
                Guid.NewGuid(),
                "USER-0001",
                "client-1",
                " ",
                Now));
    }

    [Fact]
    public void Link_message_requires_http_or_https_url()
    {
        Assert.Throws<ArgumentException>(
            () => Message.CreateLink(
                Guid.NewGuid(),
                "USER-0001",
                "client-1",
                "ftp://example.com/file",
                null,
                Now));
    }

    [Fact]
    public void Read_receipt_implies_delivery()
    {
        var receipt = MessageReceipt.Create(
            Guid.NewGuid(),
            "USER-0001");

        receipt.MarkRead(Now);

        Assert.Equal(Now, receipt.DeliveredAtUtc);
        Assert.Equal(Now, receipt.ReadAtUtc);
    }

    [Fact]
    public void Read_time_cannot_precede_delivery()
    {
        var receipt = MessageReceipt.Create(
            Guid.NewGuid(),
            "USER-0001");

        receipt.MarkDelivered(Now);

        Assert.Throws<ArgumentOutOfRangeException>(
            () => receipt.MarkRead(
                Now.AddMinutes(-1)));
    }

    [Fact]
    public void Communication_preferences_are_secure_by_default()
    {
        var preference =
            UserCommunicationPreference.CreateDefault(
                "USER-0001",
                Now);

        Assert.False(preference.AllowVoiceCalls);
        Assert.False(preference.AllowVideoCalls);
    }

    [Fact]
    public void Call_session_rejects_self_call()
    {
        Assert.Throws<ArgumentException>(
            () => CallSession.Start(
                null,
                "USER-0001",
                "USER-0001",
                "call-1",
                CommunicationCallKind.Voice,
                Now));
    }

    [Fact]
    public void Call_session_accept_then_end_follows_valid_time_order()
    {
        var call = CallSession.Start(
            null,
            "CALLER-0001",
            "CALLEE-0001",
            "call-1",
            CommunicationCallKind.Video,
            Now);

        call.Accept(Now.AddSeconds(3));
        call.End(
            CallSessionEndReason.Ended,
            Now.AddMinutes(2));

        Assert.Equal(CallSessionStatus.Ended, call.Status);
        Assert.Equal(CallSessionEndReason.Ended, call.EndReason);
    }
}
