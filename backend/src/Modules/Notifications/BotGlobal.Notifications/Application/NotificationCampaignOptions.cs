namespace BotGlobal.Notifications.Application;

public sealed class NotificationCampaignOptions
{
    public const string SectionName = "Notifications";

    public int DefaultCampaignLifetimeDays { get; init; } = 28;
    public int MinimumCampaignLifetimeDays { get; init; } = 1;
    public int MaximumCampaignLifetimeDays { get; init; } = 28;
    public NotificationWorkerOptions Worker { get; init; } = new();
    public NotificationRetryOptions Retry { get; init; } = new();
}

public sealed class NotificationWorkerOptions
{
    public bool Enabled { get; init; } = true;
    public int BatchSize { get; init; } = 100;
    public int PollIntervalSeconds { get; init; } = 10;
    public int LeaseSeconds { get; init; } = 120;
    public int MaxParallelDeliveries { get; init; } = 8;
}

public sealed class NotificationRetryOptions
{
    public int InitialDelaySeconds { get; init; } = 30;
    public int MaximumDelayMinutes { get; init; } = 60;
}
