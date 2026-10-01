using Microsoft.AspNetCore.Authorization;
using SentriCam.Application.Connections;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.SignalR;
using SentriCam.SignalR.Hubs;

namespace SentriCam.Tests.SignalR;

public sealed class DeviceHubArchitectureTests
{
    [Fact]
    public void DeviceHubIsDeviceAuthenticatedAndStronglyTyped()
    {
        var authorization = Assert.Single(
            typeof(DeviceHub).GetCustomAttributes(typeof(AuthorizeAttribute), inherit: true)
                .Cast<AuthorizeAttribute>());

        Assert.Equal(AuthorizationPolicies.Device, authorization.Policy);
        Assert.Contains(typeof(IDeviceHubServer), typeof(DeviceHub).GetInterfaces());
        Assert.Contains(
            typeof(IDeviceConnectionLifecycleService),
            typeof(DeviceHub).GetConstructors().Single().GetParameters().Select(parameter => parameter.ParameterType));
        Assert.Contains(
            typeof(SentriCam.SignalR.Devices.SignalRDeviceSessionRegistry),
            typeof(DeviceHub).GetConstructors().Single().GetParameters().Select(parameter => parameter.ParameterType));
    }

    [Fact]
    public void DeviceHubContainsNoPersistenceDependencyOrDeviceBusinessCommandMethod()
    {
        var constructorTypes = typeof(DeviceHub).GetConstructors().Single().GetParameters()
            .Select(parameter => parameter.ParameterType.FullName ?? string.Empty)
            .ToArray();
        var publicMethods = typeof(DeviceHub).GetMethods().Select(method => method.Name).ToArray();

        Assert.DoesNotContain(constructorTypes, type => type.Contains("Repository", StringComparison.Ordinal));
        Assert.DoesNotContain("StartMonitoringAsync", publicMethods);
        Assert.DoesNotContain("StopMonitoringAsync", publicMethods);
        Assert.DoesNotContain("StartRecordingAsync", publicMethods);
        Assert.DoesNotContain("StopRecordingAsync", publicMethods);
    }

    [Fact]
    public void DeviceHubAcceptsOnlyTypedCommandCompletionAtTransportBoundary()
    {
        var completion = typeof(DeviceHub).GetMethod(nameof(DeviceHub.CommandCompleted));

        Assert.NotNull(completion);
        var parameter = Assert.Single(completion.GetParameters());
        Assert.Equal(typeof(SentriCam.Contracts.Commands.DeviceCommandResultEnvelope), parameter.ParameterType);
    }

    [Fact]
    public void MonitoringHubRequiresOperatorPolicyAndIsStronglyTyped()
    {
        var authorization = Assert.Single(
            typeof(MonitoringHub).GetCustomAttributes(typeof(AuthorizeAttribute), inherit: true)
                .Cast<AuthorizeAttribute>());

        Assert.Equal(AuthorizationPolicies.DeviceControl, authorization.Policy);
        Assert.Equal(
            typeof(Microsoft.AspNetCore.SignalR.Hub<IMonitoringHubClient>),
            typeof(MonitoringHub).BaseType);
    }

    [Fact]
    public void HeartbeatExposesOnlyTheWireContractArgument()
    {
        var heartbeat = typeof(DeviceHub).GetMethod(nameof(DeviceHub.Heartbeat));

        Assert.NotNull(heartbeat);
        var parameter = Assert.Single(heartbeat.GetParameters());
        Assert.Equal(typeof(DeviceHeartbeat), parameter.ParameterType);
    }

    [Fact]
    public void OperationalHealthUsesOneStableAuthenticatedWireContract()
    {
        var report = typeof(DeviceHub).GetMethod(nameof(DeviceHub.ReportOperationalHealth));

        Assert.NotNull(report);
        var parameter = Assert.Single(report.GetParameters());
        Assert.Equal(typeof(SentriCam.Contracts.Monitoring.DeviceOperationalHealthReport), parameter.ParameterType);
    }
}
