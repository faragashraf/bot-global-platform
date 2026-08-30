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

    public int DefaultTimeToLiveDays { get; init; } = 3;

    public string RestrictedPackageName { get; init; } =
        "com.enpo.connect";
}
