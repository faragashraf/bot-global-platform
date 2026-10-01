using FluentValidation;
using Microsoft.Extensions.DependencyInjection;
using SentriCam.Application.Commands;
using SentriCam.Application.Connections;
using SentriCam.Application.Registration;
using SentriCam.Application.Monitoring;
using SentriCam.Application.Recordings;
using SentriCam.Application.Hub;
using SentriCam.Application.LiveView;
using SentriCam.Application.CameraControl;
using SentriCam.Application.Time;
using SentriCam.Application.Devices;

namespace SentriCam.Application;

public static class DependencyInjection
{
    public static IServiceCollection AddSentriCamApplication(this IServiceCollection services)
    {
        ArgumentNullException.ThrowIfNull(services);

        services.AddSingleton(TimeProvider.System);
        services.AddValidatorsFromAssemblyContaining<RegistrationRequestValidator>();
        services.AddScoped<IRegistrationService, RegistrationService>();
        services.AddScoped<SentriCam.Application.Abstractions.Authentication.IDeviceCredentialValidator, DeviceCredentialValidator>();
        services.AddSingleton<IHubTimeService, HubTimeService>();
        services.AddScoped<IRegisterDeviceCommandHandler, RegisterDeviceCommandHandler>();
        services.AddScoped<IPingCommandHandler, PingCommandHandler>();
        services.AddScoped<IGetStatusCommandHandler, GetStatusCommandHandler>();
        services.AddScoped<IDeviceControlCommandHandler, DeviceControlCommandHandler>();
        services.AddSingleton<IDeviceConnectionManager, DeviceConnectionManager>();
        services.AddSingleton<IDevicePresenceTransitionTracker, DevicePresenceTransitionTracker>();
        services.AddScoped<IDeviceConnectionLifecycleService, DeviceConnectionLifecycleService>();
        services.AddScoped<IRemoteMonitoringService, RemoteMonitoringService>();
        services.AddSingleton<IDeviceOperationalHealthRegistry, DeviceOperationalHealthRegistry>();
        services.AddScoped<IDeviceRemovalService, DeviceRemovalService>();
        services.AddSingleton<IDeviceRealtimeControl, NullDeviceRealtimeControl>();
        services.AddScoped<IRecordingService, RecordingService>();
        services.AddScoped<IRecordingThumbnailProcessor, RecordingThumbnailProcessor>();
        services.AddScoped<IHubSetupEngine, HubSetupEngine>();
        services.AddSingleton<IPairingSessionStore, InMemoryPairingSessionStore>();
        services.AddScoped<IPairingEngine, PairingEngine>();
        services.AddSingleton<ILiveSessionEngine, LiveSessionEngine>();
        services.AddSingleton<ILiveViewTransport, NullLiveViewTransport>();
        services.AddSingleton<CameraControlEngine>();
        services.AddSingleton<ICameraControlEngine>(provider =>
            provider.GetRequiredService<CameraControlEngine>());
        services.AddSingleton<ICameraControlGroupEngine, CameraControlGroupEngine>();
        services.AddSingleton<ICameraControlDispatcher>(provider =>
            provider.GetRequiredService<CameraControlEngine>());
        services.AddSingleton<ICameraControlTransport, NullCameraControlTransport>();
        services.AddSingleton<ICameraControlEventPublisher, NullCameraControlEventPublisher>();
        services.AddScoped<IDeviceCommandTransport, NullDeviceCommandTransport>();
        services.AddScoped<IMonitoringEventPublisher, NullMonitoringEventPublisher>();
        return services;
    }
}
