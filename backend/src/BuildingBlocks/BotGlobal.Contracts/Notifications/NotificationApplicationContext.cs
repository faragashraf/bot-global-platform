namespace BotGlobal.Contracts.Notifications;

public sealed record NotificationApplicationContext
{
    public NotificationApplicationContext(Guid applicationId)
    {
        if (applicationId == Guid.Empty)
        {
            throw new ArgumentException(
                "A validated application identifier is required.",
                nameof(applicationId));
        }

        ApplicationId = applicationId;
    }

    public Guid ApplicationId { get; }
}

public sealed record ApplicationAdministrationScope
{
    private ApplicationAdministrationScope(Guid? applicationId)
    {
        ApplicationId = applicationId;
    }

    public Guid? ApplicationId { get; }

    public bool IsPlatformGlobal => ApplicationId is null;

    public static ApplicationAdministrationScope PlatformGlobal { get; } =
        new((Guid?)null);

    public static ApplicationAdministrationScope ForApplication(
        Guid applicationId) =>
        new(applicationId == Guid.Empty
            ? throw new ArgumentException(
                "A valid application identifier is required.",
                nameof(applicationId))
            : applicationId);
}
