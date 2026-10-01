using Microsoft.AspNetCore.Authorization;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.LiveView;
using SentriCam.Contracts.SignalR;
using SentriCam.SignalR.Hubs;

namespace SentriCam.Tests.SignalR;

public sealed class LiveViewHubArchitectureTests
{
    [Fact]
    public void OperatorAndDeviceLiveMethodsKeepTheExistingAuthorizationBoundaries()
    {
        var monitoring = Assert.Single(typeof(MonitoringHub).GetCustomAttributes(typeof(AuthorizeAttribute), true).Cast<AuthorizeAttribute>());
        var device = Assert.Single(typeof(DeviceHub).GetCustomAttributes(typeof(AuthorizeAttribute), true).Cast<AuthorizeAttribute>());

        Assert.Equal(AuthorizationPolicies.DeviceControl, monitoring.Policy);
        Assert.Equal(AuthorizationPolicies.Device, device.Policy);
        Assert.NotNull(typeof(MonitoringHub).GetMethod("CreateLiveSession"));
        Assert.NotNull(typeof(DeviceHub).GetMethod("SubmitLiveAnswer"));
    }

    [Fact]
    public void SignalrLiveContractsNeverExposeVideoPayloadTypes()
    {
        var methods = typeof(IDeviceHubClient).GetMethods()
            .Concat(typeof(IDeviceHubServer).GetMethods())
            .Concat(typeof(IMonitoringHubClient).GetMethods())
            .Concat(typeof(MonitoringHub).GetMethods().Where(method => method.DeclaringType == typeof(MonitoringHub)));

        var payloadTypes = methods
            .SelectMany(method => method.GetParameters().Select(parameter => parameter.ParameterType))
            .Concat(methods.Select(method => method.ReturnType));

        Assert.DoesNotContain(typeof(byte[]), payloadTypes);
        Assert.DoesNotContain(typeof(Stream), payloadTypes);
        Assert.DoesNotContain(typeof(ReadOnlyMemory<byte>), payloadTypes);
        Assert.Contains(typeof(LiveSessionDescription), payloadTypes);
        Assert.Contains(typeof(LiveIceCandidate), payloadTypes);
    }
}
