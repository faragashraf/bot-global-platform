namespace BotGlobal.Communication.Application.Chat;

/// <summary>Explicit worker activation after controlled schema/storage/provider preflight.</summary>
public sealed class ChatRuntimeOptions
{
    public const string SectionName = "Communication:ChatRuntime";

    // Enables both dispatch and retention maintenance; does not gate API authorization,
    // change provider routes, create schema, or enable any other module's workers.
    public bool WorkersEnabled { get; set; }
}
