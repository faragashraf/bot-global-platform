using Asp.Versioning;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using SentriCam.Application.Commands;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Registration;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Api.Controllers;

[ApiController]
[ApiVersion(1.0)]
[Route("api/v{version:apiVersion}/devices")]
public sealed class DeviceRegistrationsController(
    IRegisterDeviceCommandHandler registerDeviceHandler) : ControllerBase
{
    [AllowAnonymous]
    [EnableRateLimiting(SecurityRateLimitPolicies.DeviceRegistration)]
    [RequestSizeLimit(65_536)]
    [HttpPost("registrations")]
    [ProducesResponseType<RegistrationResult>(StatusCodes.Status200OK)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status400BadRequest)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status401Unauthorized)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status409Conflict)]
    public async Task<ActionResult<RegistrationResult>> RegisterAsync(
        RegistrationRequest request,
        CancellationToken cancellationToken)
    {
        var result = await registerDeviceHandler.HandleAsync(
            new RegisterDevice(request),
            cancellationToken);
        return Ok(result);
    }
}
