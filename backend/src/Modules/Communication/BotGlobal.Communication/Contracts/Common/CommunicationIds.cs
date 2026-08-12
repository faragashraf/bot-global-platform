namespace BotGlobal.Communication.Contracts.Common;

public static class CommunicationIds
{
    public static string ConversationGroup(string conversationId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(conversationId);
        return $"conversation:{conversationId.Trim()}";
    }
}
