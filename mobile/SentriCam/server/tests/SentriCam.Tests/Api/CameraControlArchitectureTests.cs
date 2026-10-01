using System.Reflection;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SentriCam.Api.Controllers;
using SentriCam.Application.CameraControl;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.SignalR.Hubs;

namespace SentriCam.Tests.Api;

public sealed class CameraControlArchitectureTests
{
    [Fact]
    public void SharedCameraControlApiRequiresOperatorDeviceControlPolicy()
    {
        var authorize = typeof(CameraControlController).GetCustomAttribute<AuthorizeAttribute>();

        Assert.Equal(AuthorizationPolicies.DeviceControl, authorize!.Policy);
        Assert.Contains("camera-control/devices", typeof(CameraControlController).GetCustomAttribute<RouteAttribute>()!.Template, StringComparison.Ordinal);

        var groupAuthorize = typeof(CameraControlGroupController).GetCustomAttribute<AuthorizeAttribute>();
        Assert.Equal(AuthorizationPolicies.DeviceControl, groupAuthorize!.Policy);
        Assert.Contains("camera-control/group-actions", typeof(CameraControlGroupController).GetCustomAttribute<RouteAttribute>()!.Template, StringComparison.Ordinal);
    }

    [Fact]
    public void DeviceHubExposesOneUnifiedEnvelopeInsteadOfDashboardSpecificCommands()
    {
        var methods = typeof(DeviceHub).GetMethods(BindingFlags.Instance | BindingFlags.Public)
            .Select(method => method.Name)
            .ToArray();

        Assert.Contains(nameof(DeviceHub.ReportCameraControlState), methods);
        Assert.Contains(nameof(DeviceHub.CompleteCameraControlCommand), methods);
        Assert.DoesNotContain(methods, name => name.Contains("Dashboard", StringComparison.OrdinalIgnoreCase));
        Assert.NotNull(typeof(ICameraControlEngine).GetMethod(nameof(ICameraControlEngine.SubmitAsync)));
    }
}
