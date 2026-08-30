namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed class FcmOptions
{
    public const string SectionName =
        "Firebase";

    public bool Enabled { get; init; }

    public Guid ApplicationId { get; init; }

    public string ConfigurationReference { get; init; } =
        string.Empty;

    public string ProjectId { get; init; } =
        string.Empty;

    public string CredentialPath { get; init; } =
        string.Empty;

}
