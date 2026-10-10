using BotGlobal.Communication.Application.MobileNotifications.Fcm;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Endpoints;
using BotGlobal.Communication.Application.Delivery;
using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Application.Foundation;
using BotGlobal.Communication.Hubs;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Communication.Realtime;
using BotGlobal.Persistence;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Options;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Communication;
using BotGlobal.Communication.Application.Chat;
using BotGlobal.Communication.Application.Presence;
using BotGlobal.Communication.Infrastructure.Presence;
using FirebaseAdmin;
using FirebaseAdmin.Auth;
using Google.Apis.Auth.OAuth2;

namespace BotGlobal.Communication;

public static class CommunicationModule
{
    public const string ConnectionStringName = "Communication";
    public const string DatabaseSchema = "communication";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";

    public static IServiceCollection AddCommunicationModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);

        var connectionString =
            configuration.GetConnectionString(ConnectionStringName);

        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException(
                $"Connection string '{ConnectionStringName}' is required for the Communication module.");
        }

        services.AddDbContext<CommunicationDbContext>(
            options =>
                options.UseBotGlobalDatabase(
                    configuration,
                    ConnectionStringName,
                    connectionString,
                    DatabaseSchema,
                    MigrationsHistoryTableName));

        services.TryAddSingleton(TimeProvider.System);
        services.AddHttpContextAccessor();
        var chatRuntime = configuration.GetSection(ChatRuntimeOptions.SectionName)
            .Get<ChatRuntimeOptions>() ?? new ChatRuntimeOptions();
        services.AddOptions<ChatRuntimeOptions>()
            .Bind(configuration.GetSection(ChatRuntimeOptions.SectionName))
            .Validate(x => !x.WorkersEnabled || BotGlobalDatabaseOptions.IsPostgreSql(configuration),
                "Chat workers require the PostgreSql database provider.")
            .ValidateOnStart();
        services.AddOptions<ChatVoiceOptions>()
            .Bind(configuration.GetSection(ChatVoiceOptions.SectionName))
            .Validate(x => !string.IsNullOrWhiteSpace(x.StoragePath), "Chat voice storage path is required.")
            .Validate(x => x.PublishedRetentionDays is >= 1 and <= 7, "Chat voice retention must be between 1 and 7 days.")
            .Validate(x => x.SweepMinutes is >= 1 and <= 60, "Chat voice sweep interval must be between 1 and 60 minutes.")
            .Validate(x => !string.IsNullOrWhiteSpace(x.DecoderPath), "Chat voice decoder path is required.")
            .Validate(x => !chatRuntime.WorkersEnabled || Path.IsPathFullyQualified(x.StoragePath),
                "Active Chat workers require an absolute private durable voice storage path.")
            .ValidateOnStart();
        services.AddScoped<IChatActorResolver, ChatActorResolver>();
        services.AddScoped<ChatPolicyRegistry>();
        services.AddScoped<IChatEngine, ChatEngine>();
        services.AddSingleton<IChatVoiceStorage, PrivateChatVoiceStorage>();
        services.AddScoped<ChatDispatchProcessor>();
        services.AddSingleton<ChatConnectionRegistry>();
        services.AddScoped<ChatVoiceSweeper>();
        if (chatRuntime.WorkersEnabled) {
            services.AddHostedService<ChatMaintenanceBackgroundService>();
            services.AddHostedService<ChatDispatchBackgroundService>();
        }

        services.AddSignalR();
        services.Configure<FcmOptions>(
            configuration.GetSection(
                FcmOptions.SectionName));
        services.AddOptions<ApplicationPushProviderOptions>()
            .Bind(configuration.GetSection(
                ApplicationPushProviderOptions.SectionName))
            .Validate(
                ValidatePushProviderOptions,
                "Application push provider options are invalid.")
            .ValidateOnStart();

        var fcmOptions =
            configuration
                .GetSection(FcmOptions.SectionName)
                .Get<FcmOptions>()
            ?? new FcmOptions();
        var pushProviderOptions =
            configuration
                .GetSection(ApplicationPushProviderOptions.SectionName)
                .Get<ApplicationPushProviderOptions>()
            ?? new ApplicationPushProviderOptions();

        var fcmProfiles =
            FirebaseProfileConfiguration.Create(fcmOptions);

        ValidateFcmRuntimeBindings(
            fcmProfiles,
            pushProviderOptions);

        if (fcmProfiles.Any(profile => profile.Enabled))
        {
            // Fail fast at startup when enabled but misconfigured.
            services.AddSingleton<IFirebaseMessagingResolver>(
                _ => FirebaseAdminFactory.CreateRegistry(
                    fcmOptions));
            services.AddHostedService<
                FirebaseMessagingInitializationService>();

            services.AddSingleton<
                IFcmPushSender,
                FirebaseAdminFcmPushSender>();
        }
        else
        {
            services.AddSingleton<
                IFcmPushSender,
                DisabledFcmPushSender>();
        }

        services.AddSingleton<
            IApplicationPushProviderResolver,
            ConfigurationApplicationPushProviderResolver>();

        services.AddScoped<
            IApplicationPushNotificationDispatcher,
            ApplicationPushNotificationDispatcher>();

        services.AddScoped<
            ICommunicationDelivery,
            SignalRCommunicationDelivery>();

        services.AddScoped<
            IMobileNotificationService,
            MobileNotificationService>();
        services.AddScoped<IIncomingCallNotificationDispatcher, IncomingCallNotificationDispatcher>();

        services.AddSingleton<
            IMobileNotificationConnectionRegistry,
            MobileNotificationConnectionRegistry>();
        services.AddScoped<IApplicationAccountDeletionHandler, CommunicationAccountDeletionHandler>();

        var presenceProfiles = FirebasePresenceProfile.ReadAll(configuration);
        foreach (var profile in presenceProfiles)
            if (!profile.IsValid(out var presenceProfileError))
                throw new InvalidOperationException(presenceProfileError);
        var enabledPresenceProfiles = presenceProfiles.Where(profile => profile.Enabled).ToArray();
        if (enabledPresenceProfiles.GroupBy(profile => profile.ApplicationKey, StringComparer.Ordinal).Any(group => group.Count() > 1))
            throw new InvalidOperationException("Only one presence profile may be enabled per application.");
        services.AddOptions<PresenceOptions>()
            .Bind(configuration.GetSection(PresenceOptions.SectionName))
            .Validate(options => options.IsValid(), "Presence lease/freshness bounds are invalid.")
            .ValidateOnStart();
        services.AddSingleton<IPresenceProvider>(serviceProvider =>
        {
            if (enabledPresenceProfiles.Length == 0) return new DisabledPresenceProvider();
            var options = serviceProvider.GetRequiredService<IOptions<PresenceOptions>>();
            var clock = serviceProvider.GetRequiredService<TimeProvider>();
            var providers = new Dictionary<string, FirebasePresenceProvider>(StringComparer.Ordinal);
            foreach (var profile in enabledPresenceProfiles)
            {
                var transport = new FirebasePresenceHttpTransport(new HttpClient(
                    new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(2) });
                IFirebasePresenceTokenIssuer tokenIssuer;
                IFirebasePresenceAdminTokenSource adminTokens;
                if (profile.Emulator)
                {
                    tokenIssuer = new EmulatorPresenceTokenIssuer();
                    adminTokens = new EmulatorPresenceAdminTokenSource();
                }
                else
                {
                    var credential = CredentialFactory.FromJson<ServiceAccountCredential>(profile.CredentialJson)
                        .ToGoogleCredential()
                        .CreateScoped(
                            "https://www.googleapis.com/auth/firebase.database",
                            "https://www.googleapis.com/auth/userinfo.email");
                    var app = FirebaseApp.Create(new AppOptions
                    {
                        Credential = credential,
                        ProjectId = profile.ProjectId
                    }, $"presence-{profile.ApplicationKey}-{profile.ProjectId}");
                    tokenIssuer = new FirebaseAdminPresenceTokenIssuer(FirebaseAuth.GetAuth(app));
                    adminTokens = new GooglePresenceAdminTokenSource((ITokenAccess)credential);
                }
                providers.Add(profile.ApplicationKey, new FirebasePresenceProvider(
                    transport, profile, tokenIssuer, adminTokens, options, clock));
            }
            return new ApplicationScopedPresenceProvider(providers);
        });
        services.AddScoped<PresenceLeaseStore>();
        services.AddScoped<PresenceEngine>();
        services.AddScoped<IPresenceLeaseService>(service => service.GetRequiredService<PresenceEngine>());
        services.AddScoped<IPresenceDecisionReader>(service => service.GetRequiredService<PresenceEngine>());

        services.AddScoped<
            SignalRMobileNotificationDelivery>();

        services.AddScoped<
            IMobileNotificationDelivery,
            CompositeMobileNotificationDelivery>();

        services.AddScoped<
            IMobileNotificationTransport,
            CampaignMobileNotificationTransport>();


        services.AddSingleton<UserConnectionTracker>();

        services.AddScoped<
            ICommunicationAuthorizer,
            FoundationCommunicationAuthorizer>();

        services.AddScoped<
            ICommunicationPreferencesReader,
            FoundationCommunicationPreferencesReader>();

        return services;
    }

    private static bool ValidatePushProviderOptions(
        ApplicationPushProviderOptions options)
    {
        if (options.DefaultTimeToLiveDays is < 1 or > 28)
        {
            return false;
        }

        var keys = new HashSet<string>(
            StringComparer.OrdinalIgnoreCase);

        foreach (var provider in options.Providers)
        {
            var normalizedProvider = provider.Provider?.Trim();
            if (provider.ApplicationId == Guid.Empty
                || normalizedProvider is not (
                    PushProviderNames.FirebaseCloudMessaging
                    or PushProviderNames.ApplePushNotificationService)
                || !keys.Add(
                    $"{provider.ApplicationId:N}:{normalizedProvider}"))
            {
                return false;
            }

            if (!provider.Enabled)
            {
                continue;
            }

            if (string.IsNullOrWhiteSpace(
                    provider.ConfigurationReference))
            {
                return false;
            }

            if (normalizedProvider
                    == PushProviderNames.FirebaseCloudMessaging
                && (string.IsNullOrWhiteSpace(provider.FirebaseProjectId)
                    || string.IsNullOrWhiteSpace(
                        provider.AndroidPackageName)))
            {
                return false;
            }

            if (normalizedProvider
                    == PushProviderNames.ApplePushNotificationService
                && string.IsNullOrWhiteSpace(provider.AppleBundleId))
            {
                return false;
            }
        }

        return true;
    }

    private static void ValidateFcmRuntimeBindings(
        IReadOnlyCollection<FirebaseProfileConfiguration> profiles,
        ApplicationPushProviderOptions providers)
    {
        var fcmProviders = providers.Providers
            .Where(provider => string.Equals(
                provider.Provider?.Trim(),
                PushProviderNames.FirebaseCloudMessaging,
                StringComparison.OrdinalIgnoreCase))
            .ToList();

        foreach (var profile in profiles)
        {
            var match = fcmProviders.SingleOrDefault(provider =>
                provider.ApplicationId == profile.ApplicationId);

            if (match is null
                || match.Enabled != profile.Enabled
                || !string.Equals(
                    match.ConfigurationReference?.Trim(),
                    profile.ConfigurationReference,
                    StringComparison.Ordinal)
                || !string.Equals(
                    match.FirebaseProjectId?.Trim(),
                    profile.ProjectId,
                    StringComparison.Ordinal)
                || (profile.Enabled
                    && string.IsNullOrWhiteSpace(match.AndroidPackageName)))
            {
                throw new InvalidOperationException(
                    "Firebase runtime profiles must match their application-scoped FCM provider entries.");
            }
        }

        foreach (var provider in fcmProviders.Where(provider => provider.Enabled))
        {
            if (!profiles.Any(profile =>
                    profile.Enabled
                    && profile.ApplicationId == provider.ApplicationId))
            {
                throw new InvalidOperationException(
                    "Every enabled application-scoped FCM provider requires one enabled Firebase runtime profile.");
            }
        }
    }

    public static IEndpointRouteBuilder MapCommunicationModule(
        this IEndpointRouteBuilder endpoints,
        MobileNotificationMachineAuthorizationOptions notificationAuthorization)
    {
        endpoints.MapHub<CommunicationHub>(
            "/hubs/communications");

        endpoints.MapHub<MobileNotificationsHub>(
            MobileNotificationRealtimeContract.HubPath);

        endpoints.MapHub<ChatHub>(ChatContract.HubPath);

        endpoints.MapCommunicationTestEndpoints();

        endpoints.MapMobileNotificationEndpoints(
            notificationAuthorization);

        endpoints.MapChatEndpoints();

        endpoints.MapPresenceEndpoints();

        return endpoints;
    }
}

internal sealed class EmulatorPresenceTokenIssuer : IFirebasePresenceTokenIssuer
{
    public Task<string> IssueAsync(string uid, string leaseId, string connectionId, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) =>
        throw new InvalidOperationException("Inject a local emulator token issuer for the explicit demo profile.");
}

internal sealed class EmulatorPresenceAdminTokenSource : IFirebasePresenceAdminTokenSource
{
    public Task<string> GetAsync(CancellationToken cancellationToken) => Task.FromResult("owner");
}
