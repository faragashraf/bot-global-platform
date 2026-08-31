using Microsoft.AspNetCore.SignalR;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.SignalR.Authentication;

public sealed class DeviceUserIdProvider : IUserIdProvider
{
    public string? GetUserId(HubConnectionContext connection) =>
        connection.User.FindFirst(AuthenticationClaimNames.DeviceId)?.Value;
}
