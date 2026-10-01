using System.Net;
using Asp.Versioning;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Options;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Common;
using SentriCam.Application.Hub;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.Hub;
using SentriCam.Contracts.Monitoring;
using SentriCam.Contracts.Registration;
using SentriCam.Infrastructure.Authentication;

namespace SentriCam.Api.Controllers;

[ApiController]
[ApiVersion(1.0)]
[Route("api/v{version:apiVersion}/hub")]
public sealed class HubController(
    IHubSetupEngine setupEngine,
    IHubSetupStore setupStore,
    IHubStatusProbe statusProbe,
    IPairingEngine pairingEngine,
    IOperatorAccessTokenIssuer operatorTokenIssuer,
    IOptions<JwtOptions> jwtOptions,
    TimeProvider timeProvider) : ControllerBase
{
    [AllowAnonymous]
    [HttpGet("setup")]
    [ProducesResponseType<HubSetupState>(StatusCodes.Status200OK)]
    public ActionResult<HubSetupState> GetSetup()
    {
        EnsureLocalRequest();
        return Ok(setupEngine.GetState());
    }

    [AllowAnonymous]
    [RequestSizeLimit(65_536)]
    [HttpPut("setup/draft")]
    [ProducesResponseType<HubSetupState>(StatusCodes.Status200OK)]
    public async Task<ActionResult<HubSetupState>> SaveDraftAsync(
        HubSetupDraft draft,
        CancellationToken cancellationToken)
    {
        EnsureSetupAccess();
        return Ok(await setupEngine.SaveDraftAsync(draft, cancellationToken));
    }

    [AllowAnonymous]
    [RequestSizeLimit(65_536)]
    [HttpPost("setup/database/test")]
    [ProducesResponseType<HubConnectionTestResult>(StatusCodes.Status200OK)]
    public async Task<ActionResult<HubConnectionTestResult>> TestDatabaseAsync(
        HubConnectionTestRequest request,
        CancellationToken cancellationToken)
    {
        EnsureSetupAccess();
        return Ok(await setupEngine.TestDatabaseAsync(request, cancellationToken));
    }

    [AllowAnonymous]
    [RequestSizeLimit(65_536)]
    [HttpPost("setup/configure")]
    [ProducesResponseType<HubConfigurationResult>(StatusCodes.Status200OK)]
    public async Task<ActionResult<HubConfigurationResult>> ConfigureAsync(
        HubSetupDraft draft,
        CancellationToken cancellationToken)
    {
        EnsureSetupAccess();
        return Ok(await setupEngine.ConfigureAsync(draft, cancellationToken));
    }

    [AllowAnonymous]
    [EnableRateLimiting(SecurityRateLimitPolicies.DeviceRegistration)]
    [HttpPost("session")]
    [ProducesResponseType<OperatorTokenResponse>(StatusCodes.Status200OK)]
    public ActionResult<OperatorTokenResponse> CreateSession()
    {
        if (!setupStore.Current.IsConfigured)
        {
            throw new ResourceConflictException("Finish Hub setup before opening the dashboard.");
        }
        EnsureLocalRequest();
        var issuedAtUtc = timeProvider.GetUtcNow();
        var token = operatorTokenIssuer.Issue(issuedAtUtc);
        return Ok(new OperatorTokenResponse(
            token.Value,
            token.ExpiresAtUtc,
            "Bearer",
            jwtOptions.Value.DevelopmentOperatorDisplayName));
    }

    [Authorize(Roles = AuthorizationRoles.DeviceOperator)]
    [HttpGet("status")]
    [ProducesResponseType<HubStatus>(StatusCodes.Status200OK)]
    public async Task<ActionResult<HubStatus>> GetStatusAsync(CancellationToken cancellationToken) =>
        Ok(await statusProbe.ReadAsync(cancellationToken));

    [Authorize(Roles = AuthorizationRoles.DeviceOperator)]
    [HttpPost("pairing-sessions")]
    [ProducesResponseType<PairingSession>(StatusCodes.Status200OK)]
    public ActionResult<PairingSession> CreatePairingSession() => Ok(pairingEngine.CreateSession());

    [AllowAnonymous]
    [EnableRateLimiting(SecurityRateLimitPolicies.DeviceRegistration)]
    [RequestSizeLimit(65_536)]
    [HttpPost("pairing-sessions/complete")]
    [ProducesResponseType<RegistrationResult>(StatusCodes.Status200OK)]
    public async Task<ActionResult<RegistrationResult>> CompletePairingAsync(
        CompletePairingRequest request,
        CancellationToken cancellationToken) =>
        Ok(await pairingEngine.CompleteAsync(request, cancellationToken));

    private void EnsureSetupAccess()
    {
        EnsureLocalRequest();
        if (setupStore.Current.IsConfigured
            && (User.Identity?.IsAuthenticated != true
                || !User.IsInRole(AuthorizationRoles.DeviceOperator)))
        {
            throw new AccessDeniedException("The Hub is already configured.");
        }
    }

    private void EnsureLocalRequest()
    {
        if (!IsLocalRequest(HttpContext.Connection.RemoteIpAddress))
        {
            throw new AccessDeniedException("Local Hub access is available only on the home or office network.");
        }
    }

    private static bool IsLocalRequest(IPAddress? address)
    {
        if (address is null || IPAddress.IsLoopback(address))
        {
            return true;
        }
        if (address.IsIPv4MappedToIPv6)
        {
            address = address.MapToIPv4();
        }
        if (address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6)
        {
            var ipv6Bytes = address.GetAddressBytes();
            return (ipv6Bytes[0] & 0xfe) == 0xfc
                || (ipv6Bytes[0] == 0xfe && (ipv6Bytes[1] & 0xc0) == 0x80);
        }
        if (address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
        {
            return false;
        }
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10
            || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
            || (bytes[0] == 192 && bytes[1] == 168);
    }
}
