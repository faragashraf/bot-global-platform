using System.Security.Claims;
using Asp.Versioning;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SentriCam.Application.CameraControl;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.CameraControl;

namespace SentriCam.Api.Controllers;

[ApiController]
[ApiVersion(1.0)]
[Authorize(Policy = AuthorizationPolicies.DeviceControl)]
[Route("api/v{version:apiVersion}/camera-control/devices")]
public sealed class CameraControlController(ICameraControlEngine cameraControl) : ControllerBase
{
    [HttpGet("{deviceId:guid}")]
    [ProducesResponseType<CameraControlCenterView>(StatusCodes.Status200OK)]
    public Task<CameraControlCenterView> GetAsync(
        Guid deviceId,
        CancellationToken cancellationToken) =>
        cameraControl.GetAsync(deviceId, cancellationToken);

    [HttpPost("{deviceId:guid}/commands")]
    [ProducesResponseType<CameraControlCommandView>(StatusCodes.Status202Accepted)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status409Conflict)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status422UnprocessableEntity)]
    public async Task<ActionResult<CameraControlCommandView>> SubmitAsync(
        Guid deviceId,
        CameraControlCommandRequest request,
        CancellationToken cancellationToken)
    {
        var command = await cameraControl.SubmitAsync(
            deviceId,
            request,
            Actor(),
            cancellationToken);
        // There is no single-command GET resource. Returning AcceptedAtAction here previously
        // targeted the MVC-suppressed action name "GetAsync" and failed during response formatting
        // after the command had already been persisted. Keep the structured command payload without
        // manufacturing a Location header for a resource that does not exist.
        return Accepted(command);
    }

    [HttpDelete("{deviceId:guid}/commands/{commandId:guid}")]
    [ProducesResponseType<CameraControlCommandView>(StatusCodes.Status200OK)]
    public Task<CameraControlCommandView> CancelAsync(
        Guid deviceId,
        Guid commandId,
        CancellationToken cancellationToken) =>
        cameraControl.CancelAsync(deviceId, commandId, Actor(), cancellationToken);

    private CameraControlActor Actor()
    {
        var subject = User.FindFirstValue("sub")
            ?? User.FindFirstValue(ClaimTypes.NameIdentifier);
        return new CameraControlActor(subject ?? string.Empty);
    }
}
