namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

public sealed class FcmOptions
{
    public const string SectionName =
        "Firebase";

    public bool Enabled { get; init; }

    public string ProjectId { get; init; } =
        string.Empty;

    public string CredentialPath { get; init; } =
        string.Empty;
}
