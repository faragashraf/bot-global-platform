using System.Security.Claims;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Common;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Domain.Devices;

namespace SentriCam.Api.Authentication;

public sealed class ClaimsDeviceRequestAuthorizer(IHttpContextAccessor httpContextAccessor)
    : IDeviceRequestAuthorizer
{
    public void EnsureCanAccess(DeviceId deviceId)
    {
        var principal = httpContextAccessor.HttpContext?.User;
        if (principal?.Identity?.IsAuthenticated == true
            && principal.IsInRole(AuthorizationRoles.DeviceOperator))
        {
            return;
        }

        var deviceIdClaim = principal?.FindFirstValue(AuthenticationClaimNames.DeviceId);
        if (principal?.Identity?.IsAuthenticated != true
            || !Guid.TryParse(deviceIdClaim, out var authenticatedDeviceId)
            || authenticatedDeviceId != deviceId.Value)
        {
            throw new AccessDeniedException(
                "The authenticated principal cannot access the requested device resource.");
        }
    }
}
