using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Common;
using SentriCam.Contracts.Commands;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Commands;

public sealed class PingCommandHandler(
    IDeviceRepository deviceRepository,
    IUnitOfWork unitOfWork,
    TimeProvider timeProvider,
    IDeviceRequestAuthorizer requestAuthorizer) : IPingCommandHandler
{
    public async Task<PingResult> HandleAsync(
        Ping command,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(command);
        var deviceId = DeviceId.From(command.DeviceId);
        requestAuthorizer.EnsureCanAccess(deviceId);
        var device = await deviceRepository.GetByIdAsync(deviceId, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), command.DeviceId);
        var now = timeProvider.GetUtcNow();

        device.MarkSeen(now);
        await unitOfWork.SaveChangesAsync(cancellationToken);

        return new PingResult(command.DeviceId, now, device.LastSeenAtUtc!.Value);
    }
}
