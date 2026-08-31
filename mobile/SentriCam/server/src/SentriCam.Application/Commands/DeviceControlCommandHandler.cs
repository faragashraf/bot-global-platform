using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Common;
using SentriCam.Contracts.Commands;
using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Commands;

public sealed class DeviceControlCommandHandler(
    IDeviceRepository deviceRepository,
    IDeviceCommandRepository commandRepository,
    IUnitOfWork unitOfWork,
    TimeProvider timeProvider,
    IDeviceRequestAuthorizer requestAuthorizer) : IDeviceControlCommandHandler
{
    public async Task<CommandSubmissionResult> HandleAsync(
        IDeviceControlCommand command,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(command);
        var deviceId = DeviceId.From(command.DeviceId);
        requestAuthorizer.EnsureCanAccess(deviceId);
        var device = await deviceRepository.GetByIdAsync(deviceId, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), command.DeviceId);

        if (!device.IsEnabled)
        {
            throw new DomainValidationException("Commands cannot be queued for a disabled device.");
        }

        var now = timeProvider.GetUtcNow();
        var correlationId = string.IsNullOrWhiteSpace(command.CorrelationId)
            ? Guid.NewGuid().ToString("N")
            : command.CorrelationId.Trim();
        var commandKind = MapCommand(command.CommandType);
        var existingCommand = await commandRepository.GetByCorrelationIdAsync(
            deviceId,
            correlationId,
            cancellationToken);
        if (existingCommand is not null)
        {
            if (existingCommand.Kind != commandKind)
            {
                throw new DomainValidationException(
                    "A correlation id cannot be reused for a different command type.");
            }

            return new CommandSubmissionResult(
                existingCommand.CommandId,
                command.DeviceId,
                command.CommandType,
                existingCommand.CorrelationId,
                existingCommand.CreatedAtUtc);
        }

        var queuedCommand = DeviceCommand.Queue(
            deviceId,
            commandKind,
            correlationId,
            now,
            expiresAtUtc: now.AddSeconds(30),
            DeviceCommandOrigin.Application);

        commandRepository.Add(queuedCommand);
        await unitOfWork.SaveChangesAsync(cancellationToken);

        return new CommandSubmissionResult(
            queuedCommand.CommandId,
            command.DeviceId,
            command.CommandType,
            queuedCommand.CorrelationId,
            now);
    }

    private static DeviceCommandKind MapCommand(DeviceCommandType commandType) => commandType switch
    {
        DeviceCommandType.StartMonitoring => DeviceCommandKind.StartMonitoring,
        DeviceCommandType.StopMonitoring => DeviceCommandKind.StopMonitoring,
        DeviceCommandType.StartRecording => DeviceCommandKind.StartRecording,
        DeviceCommandType.StopRecording => DeviceCommandKind.StopRecording,
        DeviceCommandType.RestartMonitoring => DeviceCommandKind.RestartMonitoring,
        DeviceCommandType.Ping => DeviceCommandKind.Ping,
        DeviceCommandType.GetStatus => DeviceCommandKind.GetStatus,
        _ => throw new ArgumentOutOfRangeException(nameof(commandType), commandType, "Unknown device command."),
    };
}
