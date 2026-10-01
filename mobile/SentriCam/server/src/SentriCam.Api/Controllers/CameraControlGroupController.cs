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
[Route("api/v{version:apiVersion}/camera-control/group-actions")]
public sealed class CameraControlGroupController(ICameraControlGroupEngine groups) : ControllerBase
{
    [HttpPost]
    [ProducesResponseType<CameraControlGroupCommandResult>(StatusCodes.Status202Accepted)]
    public async Task<ActionResult<CameraControlGroupCommandResult>> SubmitAsync(
        CameraControlGroupCommandRequest request,
        CancellationToken cancellationToken)
    {
        var subject = User.FindFirstValue("sub")
            ?? User.FindFirstValue(ClaimTypes.NameIdentifier)
            ?? string.Empty;
        var result = await groups.SubmitAsync(request, new CameraControlActor(subject), cancellationToken);
        return Accepted(result);
    }
}
