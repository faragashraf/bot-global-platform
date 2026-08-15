namespace BotGlobal.Pairing.Domain;

public sealed class MobilePushRegistration
{
    private MobilePushRegistration()
    {
    }

    public MobilePushRegistration(
        Guid mobileDeviceId,
        string provider,
        string registrationToken,
        DateTimeOffset now)
    {
        if (mobileDeviceId == Guid.Empty)
        {
            throw new ArgumentException(
                "Mobile device id is required.",
                nameof(mobileDeviceId));
        }

        ArgumentException.ThrowIfNullOrWhiteSpace(provider);
        ArgumentException.ThrowIfNullOrWhiteSpace(registrationToken);

        Id = Guid.NewGuid();
        MobileDeviceId = mobileDeviceId;
        Provider = NormalizeProvider(provider);
        RegistrationToken = registrationToken.Trim();
        CreatedAtUtc = now;
        UpdatedAtUtc = now;
    }

    public Guid Id { get; private set; }

    public Guid MobileDeviceId { get; private set; }

    public string Provider { get; private set; } = string.Empty;

    public string RegistrationToken { get; private set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public DateTimeOffset UpdatedAtUtc { get; private set; }

    public DateTimeOffset? InvalidatedAtUtc { get; private set; }

    public void Refresh(
        string registrationToken,
        DateTimeOffset now)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            registrationToken);

        RegistrationToken =
            registrationToken.Trim();

        UpdatedAtUtc = now;
        InvalidatedAtUtc = null;
    }

    public void Invalidate(
        DateTimeOffset now)
    {
        if (InvalidatedAtUtc.HasValue)
        {
            return;
        }

        InvalidatedAtUtc = now;
        UpdatedAtUtc = now;
    }

    private static string NormalizeProvider(
        string provider) =>
        provider.Trim().ToLowerInvariant();
}
