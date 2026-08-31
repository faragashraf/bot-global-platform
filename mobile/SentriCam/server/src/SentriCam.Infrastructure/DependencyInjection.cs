using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Messaging;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Infrastructure.Authentication;
using SentriCam.Infrastructure.Persistence;
using SentriCam.Infrastructure.Persistence.Outbox;
using SentriCam.Infrastructure.Persistence.Repositories;
using SentriCam.Application.Recordings;
using SentriCam.Infrastructure.Storage;
using SentriCam.Application.Hub;
using SentriCam.Infrastructure.Hub;
using SentriCam.Application.CameraControl;
using SentriCam.Infrastructure.CameraControl;

namespace SentriCam.Infrastructure;

public static class DependencyInjection
{
    public static IServiceCollection AddSentriCamInfrastructure(
        this IServiceCollection services,
        IConfiguration configuration,
        FileHubSetupStore hubSetupStore)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(configuration);

        ArgumentNullException.ThrowIfNull(hubSetupStore);
        services.AddSingleton<IHubSetupStore>(hubSetupStore);
        services.AddSingleton<ICameraControlStore>(
            new FileCameraControlStore(hubSetupStore.RootPath));
        services.AddDbContext<SentriCamDbContext>((provider, options) =>
        {
            var current = provider.GetRequiredService<IHubSetupStore>().Current;
            options.ConfigureSentriCamProvider(
                current.Draft.Database.Provider,
                current.DatabaseConnectionString);
        });

        services.AddOptions<JwtOptions>()
            .Bind(configuration.GetSection(JwtOptions.SectionName))
            .Validate(options => !string.IsNullOrWhiteSpace(options.Issuer), "JWT issuer is required.")
            .Validate(options => !string.IsNullOrWhiteSpace(options.Audience), "JWT audience is required.")
            .Validate(options => options.SigningKey.Length >= 32, "JWT signing key must be at least 32 characters.")
            .Validate(
                options => options.AccessTokenLifetimeMinutes is > 0 and <= 1_440,
                "JWT access token lifetime must be between 1 and 1440 minutes.")
            .Validate(
                options => options.DevelopmentOperatorTokenLifetimeMinutes is > 0 and <= 1_440,
                "Development operator token lifetime must be between 1 and 1440 minutes.")
            .Validate(
                options => !string.IsNullOrWhiteSpace(options.DevelopmentOperatorSubject),
                "Development operator subject is required.")
            .Validate(
                options => !string.IsNullOrWhiteSpace(options.DevelopmentOperatorDisplayName),
                "Development operator display name is required.")
            .ValidateOnStart();

        services.AddOptions<RecordingStorageOptions>()
            .Bind(configuration.GetSection(RecordingStorageOptions.SectionName))
            .Validate(options => !string.IsNullOrWhiteSpace(options.RootPath), "Recording storage root is required.")
            .Validate(
                options => options.MaximumUploadBytes is >= 1_048_576 and <= 53_687_091_200,
                "Recording maximum upload size must be between 1 MiB and 50 GiB.")
            .Validate(options => options.ThumbnailWidth is >= 160 and <= 1920, "Thumbnail width is invalid.")
            .Validate(options => options.ThumbnailHeight is >= 90 and <= 1080, "Thumbnail height is invalid.")
            .Validate(options => options.ThumbnailJpegQuality is >= 2 and <= 10, "Thumbnail JPEG quality is invalid.")
            .Validate(options => options.ThumbnailTimeoutSeconds is >= 5 and <= 300, "Thumbnail timeout is invalid.")
            .ValidateOnStart();

        services.AddScoped<IDeviceRepository, DeviceRepository>();
        services.AddScoped<IDeviceRegistrationRepository, DeviceRegistrationRepository>();
        services.AddScoped<IDeviceCommandRepository, DeviceCommandRepository>();
        services.AddScoped<IDeviceEventRepository, DeviceEventRepository>();
        services.AddScoped<IDeviceConnectionRepository, DeviceConnectionRepository>();
        services.AddScoped<IDeviceSnapshotRepository, DeviceSnapshotRepository>();
        services.AddScoped<IRecordingRepository, RecordingRepository>();
        services.AddSingleton<LocalFolderRecordingStorageProvider>();
        services.AddSingleton<IRecordingStorageProvider>(provider =>
            provider.GetRequiredService<LocalFolderRecordingStorageProvider>());
        services.AddSingleton<IRecordingThumbnailGenerator, LocalRecordingThumbnailGenerator>();
        services.AddSingleton<IRecordingThumbnailScheduler, RecordingThumbnailScheduler>();
        services.AddScoped<IUnitOfWork>(provider => provider.GetRequiredService<SentriCamDbContext>());
        services.AddScoped<IDeviceAccessTokenIssuer, JwtDeviceAccessTokenIssuer>();
        services.AddScoped<IOperatorAccessTokenIssuer, JwtOperatorAccessTokenIssuer>();
        services.AddScoped<IOutboxWriter, OutboxWriter>();
        services.AddScoped<IOutboxStore, OutboxStore>();
        services.AddScoped<IHubProvisioner, HubProvisioner>();
        services.AddScoped<IHubStatusProbe, HubStatusProbe>();
        services.AddSingleton<IMediaEngineProbe, MediaEngineProbe>();
        services.AddOptions<AdvertisedHubUrlOptions>()
            .Bind(configuration.GetSection(AdvertisedHubUrlOptions.SectionName));
        services.AddSingleton<ILocalHubIdentitySource, SystemLocalHubIdentitySource>();
        services.AddSingleton<IAdvertisedHubUrlResolver, AdvertisedHubUrlResolver>();
        services.AddHostedService<HubStartupProvisioningService>();
        return services;
    }
}
