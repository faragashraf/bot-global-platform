using Asp.Versioning;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SentriCam.Application.Time;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.Time;

namespace SentriCam.Api.Controllers;

[ApiController]
[ApiVersion(1.0)]
[Authorize(Policy = AuthorizationPolicies.Device)]
[Route("api/v{version:apiVersion}/device/time")]
public sealed class HubTimeController(IHubTimeService hubTime) : ControllerBase
{
    [HttpGet]
    [ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
    [ProducesResponseType<HubTimeSnapshot>(StatusCodes.Status200OK)]
    public ActionResult<HubTimeSnapshot> Get() => Ok(hubTime.GetCurrent());
}
