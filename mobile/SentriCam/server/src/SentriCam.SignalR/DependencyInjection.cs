using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.DependencyInjection;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.SignalR.Authentication;
using SentriCam.SignalR.Hubs;
using SentriCam.Application.Monitoring;
using SentriCam.SignalR.Monitoring;
using SentriCam.Application.LiveView;
using SentriCam.SignalR.LiveView;
using SentriCam.Application.CameraControl;
using SentriCam.SignalR.CameraControl;
using SentriCam.Application.Devices;
using SentriCam.Application.Connections;
using SentriCam.SignalR.Devices;

namespace SentriCam.SignalR;

public static class DependencyInjection
{
    public static IServiceCollection AddSentriCamSignalR(this IServiceCollection services)
    {
        ArgumentNullException.ThrowIfNull(services);
        services.AddSignalR(options =>
        {
            options.EnableDetailedErrors = false;
            options.MaximumReceiveMessageSize = 65_536;
            options.MaximumParallelInvocationsPerClient = 1;
        });
        services.AddSingleton<IUserIdProvider, DeviceUserIdProvider>();
        services.AddScoped<IDeviceCommandTransport, SignalRDeviceCommandTransport>();
        services.AddScoped<IMonitoringEventPublisher, SignalRMonitoringEventPublisher>();
        services.AddSingleton<ILiveViewTransport, SignalRLiveViewTransport>();
        services.AddSingleton<ICameraControlTransport, SignalRCameraControlTransport>();
        services.AddSingleton<ICameraControlEventPublisher, SignalRCameraControlEventPublisher>();
        services.AddSingleton<SignalRDeviceSessionRegistry>();
        services.AddSingleton<IDeviceRealtimeControl, SignalRDeviceRealtimeControl>();
        return services;
    }

    public static HubEndpointConventionBuilder MapSentriCamDeviceHub(
        this IEndpointRouteBuilder endpoints)
    {
        ArgumentNullException.ThrowIfNull(endpoints);
        return endpoints
            .MapHub<DeviceHub>("/hubs/device")
            .RequireRateLimiting(SecurityRateLimitPolicies.SignalRConnections);
    }

    public static HubEndpointConventionBuilder MapSentriCamMonitoringHub(
        this IEndpointRouteBuilder endpoints)
    {
        ArgumentNullException.ThrowIfNull(endpoints);
        return endpoints
            .MapHub<MonitoringHub>("/hubs/monitoring")
            .RequireRateLimiting(SecurityRateLimitPolicies.SignalRConnections);
    }
}
