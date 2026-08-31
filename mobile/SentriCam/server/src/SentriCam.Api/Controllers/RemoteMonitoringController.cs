using Asp.Versioning;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SentriCam.Application.Monitoring;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Application.Devices;
using System.Security.Claims;

namespace SentriCam.Api.Controllers;

[ApiController]
[ApiVersion(1.0)]
[Authorize(Policy = AuthorizationPolicies.DeviceControl)]
[Route("api/v{version:apiVersion}/monitoring/devices")]
public sealed class RemoteMonitoringController(
    IRemoteMonitoringService monitoring,
    IDeviceRemovalService removals) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType<IReadOnlyList<DeviceLiveState>>(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<DeviceLiveState>>> GetDevicesAsync(
        CancellationToken cancellationToken) =>
        Ok(await monitoring.GetDevicesAsync(cancellationToken));

    [HttpGet("{deviceId:guid}")]
    [ProducesResponseType<DeviceMonitoringDetails>(StatusCodes.Status200OK)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<DeviceMonitoringDetails>> GetDeviceAsync(
        Guid deviceId,
        CancellationToken cancellationToken) =>
        Ok(await monitoring.GetDeviceAsync(deviceId, cancellationToken));

    [HttpPost("{deviceId:guid}/commands/ping")]
    public Task<RemoteCommandView> PingAsync(
        Guid deviceId,
        CancellationToken cancellationToken) =>
        monitoring.SubmitAsync(new PingDevice(deviceId), cancellationToken);

    [HttpPost("{deviceId:guid}/commands/refresh")]
    public Task<RemoteCommandView> RefreshAsync(
        Guid deviceId,
        CancellationToken cancellationToken) =>
        monitoring.SubmitAsync(new RefreshDeviceStatus(deviceId), cancellationToken);

    [HttpPost("{deviceId:guid}/commands/start-monitoring")]
    public Task<RemoteCommandView> StartMonitoringAsync(
        Guid deviceId,
        CancellationToken cancellationToken) =>
        monitoring.SubmitAsync(new StartMonitoring(deviceId), cancellationToken);

    [HttpPost("{deviceId:guid}/commands/stop-monitoring")]
    public Task<RemoteCommandView> StopMonitoringAsync(
        Guid deviceId,
        CancellationToken cancellationToken) =>
        monitoring.SubmitAsync(new StopMonitoring(deviceId), cancellationToken);

    [Authorize(Policy = AuthorizationPolicies.DeviceAdministration)]
    [HttpGet("{deviceId:guid}/removal")]
    [ProducesResponseType<DeviceRemovalImpact>(StatusCodes.Status200OK)]
    public Task<DeviceRemovalImpact> GetRemovalImpactAsync(
        Guid deviceId,
        CancellationToken cancellationToken) =>
        removals.GetImpactAsync(deviceId, cancellationToken);

    [Authorize(Policy = AuthorizationPolicies.DeviceAdministration)]
    [HttpDelete("{deviceId:guid}")]
    [ProducesResponseType<DeviceRemovalResult>(StatusCodes.Status200OK)]
    [ProducesResponseType<DeviceRemovalImpact>(StatusCodes.Status409Conflict)]
    public async Task<ActionResult<DeviceRemovalResult>> RemoveDeviceAsync(
        Guid deviceId,
        RemoveDeviceRequest request,
        CancellationToken cancellationToken)
    {
        var impact = await removals.GetImpactAsync(deviceId, cancellationToken);
        if (!impact.CanRemove)
        {
            return Conflict(impact);
        }

        var subject = User.FindFirstValue("sub") ?? "administrator";
        return Ok(await removals.RemoveAsync(
            deviceId,
            request,
            new DeviceRemovalActor(subject),
            cancellationToken));
    }
}
