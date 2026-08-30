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

        if (fcmOptions.Enabled)
        {
            ValidateFcmRuntimeBinding(
                fcmOptions,
                pushProviderOptions);

            // Fail fast at startup when enabled but misconfigured.
            services.AddSingleton(
                FirebaseAdminFactory.CreateMessaging(
                    fcmOptions));

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

    private static void ValidateFcmRuntimeBinding(
        FcmOptions fcm,
        ApplicationPushProviderOptions providers)
    {
        var match = providers.Providers.SingleOrDefault(provider =>
            provider.ApplicationId == fcm.ApplicationId
            && string.Equals(
                provider.Provider?.Trim(),
                PushProviderNames.FirebaseCloudMessaging,
                StringComparison.OrdinalIgnoreCase));

        if (match is null
            || !match.Enabled
            || !string.Equals(
                match.ConfigurationReference?.Trim(),
                fcm.ConfigurationReference?.Trim(),
                StringComparison.Ordinal)
            || !string.Equals(
                match.FirebaseProjectId?.Trim(),
                fcm.ProjectId?.Trim(),
                StringComparison.Ordinal)
            || string.IsNullOrWhiteSpace(match.AndroidPackageName))
        {
            throw new InvalidOperationException(
                "Enabled Firebase runtime configuration must match one enabled application-scoped FCM provider entry.");
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
