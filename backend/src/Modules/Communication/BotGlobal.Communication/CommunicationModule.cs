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
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Contracts.Calling;

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
                options.UseSqlServer(
                    connectionString,
                    sqlServer =>
                        sqlServer.MigrationsHistoryTable(
                            MigrationsHistoryTableName,
                            DatabaseSchema)));

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

        endpoints.MapCommunicationTestEndpoints();

        endpoints.MapMobileNotificationEndpoints(
            notificationAuthorization);

        return endpoints;
    }
}
