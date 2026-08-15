using System.Security.Claims;
using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Communication.Hubs;

[Authorize(
    AuthenticationSchemes =
        MobileDeviceAuthenticationDefaults.Scheme)]
public sealed class MobileNotificationsHub(
    IMobileNotificationConnectionRegistry connections)
    : Hub
{
    public override async Task OnConnectedAsync()
    {
        var deviceId =
            RequireDeviceId();

        connections.Connected(deviceId);

        await Groups.AddToGroupAsync(
            Context.ConnectionId,
            MobileNotificationRealtimeContract.DeviceGroup(
                deviceId));

        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(
        Exception? exception)
    {
        var deviceId =
            TryGetDeviceId();

        if (deviceId.HasValue)
        {
            connections.Disconnected(
                deviceId.Value);
        }

        await base.OnDisconnectedAsync(exception);
    }

    private Guid RequireDeviceId() =>
        TryGetDeviceId()
        ?? throw new HubException(
            "Authenticated mobile device id is unavailable.");

    private Guid? TryGetDeviceId()
    {
        var raw =
            Context.User?.FindFirstValue(
                MobileDeviceAuthenticationDefaults.DeviceIdClaim);

        return Guid.TryParse(
            raw,
            out var deviceId)
            ? deviceId
            : null;
    }
}
