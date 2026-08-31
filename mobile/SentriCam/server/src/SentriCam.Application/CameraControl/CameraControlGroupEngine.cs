using SentriCam.Application.Common;
using SentriCam.Contracts.CameraControl;
using SentriCam.Domain.Common;

namespace SentriCam.Application.CameraControl;

public interface ICameraControlGroupEngine
{
    Task<CameraControlGroupCommandResult> SubmitAsync(
        CameraControlGroupCommandRequest request,
        CameraControlActor actor,
        CancellationToken cancellationToken = default);
}

/// <summary>
/// Aggregates independent commands without duplicating validation, dispatch,
/// persistence, retry, or audit behavior owned by CameraControlEngine.
/// </summary>
public sealed class CameraControlGroupEngine(ICameraControlEngine commands) : ICameraControlGroupEngine
{
    private const int MaximumDevices = 16;

    public async Task<CameraControlGroupCommandResult> SubmitAsync(
        CameraControlGroupCommandRequest request,
        CameraControlActor actor,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.DeviceIds is null || request.DeviceIds.Count == 0)
        {
            throw new DomainValidationException("Select at least one camera.");
        }
        var deviceIds = request.DeviceIds.Distinct().ToArray();
        if (deviceIds.Contains(Guid.Empty))
        {
            throw new DomainValidationException("Every selected camera must have a valid identity.");
        }
        if (deviceIds.Length > MaximumDevices)
        {
            throw new DomainValidationException($"A group action supports at most {MaximumDevices} cameras.");
        }
        if (!string.Equals(request.Control, CameraControlIds.Recording, StringComparison.Ordinal)
            || request.Value.Text is not (CameraControlValues.Start or CameraControlValues.Stop))
        {
            throw new DomainValidationException("v0.6 group commands support Start Recording and Stop Recording only.");
        }

        var correlationId = string.IsNullOrWhiteSpace(request.CorrelationId)
            ? Guid.NewGuid().ToString("N")
            : request.CorrelationId.Trim();
        if (correlationId.Length > 64)
        {
            throw new DomainValidationException("Group correlation id cannot exceed 64 characters.");
        }

        var items = new List<CameraControlGroupCommandItem>(deviceIds.Length);
        foreach (var deviceId in deviceIds)
        {
            try
            {
                var command = await commands.SubmitAsync(
                    deviceId,
                    new CameraControlCommandRequest(
                        request.Control,
                        request.Value,
                        $"{correlationId}:{deviceId:N}"),
                    actor,
                    cancellationToken);
                items.Add(new CameraControlGroupCommandItem(deviceId, true, command, null, null));
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception failure) when (failure is DomainValidationException
                or ResourceConflictException
                or ResourceNotFoundException
                or AccessDeniedException)
            {
                items.Add(new CameraControlGroupCommandItem(
                    deviceId,
                    false,
                    null,
                    ErrorCode(failure),
                    failure.Message));
            }
        }

        return new CameraControlGroupCommandResult(
            correlationId,
            items,
            items.Count(item => item.Succeeded),
            items.Count(item => !item.Succeeded));
    }

    private static string ErrorCode(Exception failure) => failure switch
    {
        ResourceConflictException => "resource_conflict",
        ResourceNotFoundException => "device_not_found",
        AccessDeniedException => "access_denied",
        _ => "capability_validation_failed",
    };
}
