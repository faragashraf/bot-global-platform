using System.Collections.Concurrent;
using SentriCam.Application.Common;
using SentriCam.Application.Connections;
using SentriCam.Contracts.CameraControl;
using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;
using Microsoft.Extensions.Logging;
using SentriCam.Contracts.Motion;
using System.Globalization;

namespace SentriCam.Application.CameraControl;

public sealed class CameraControlEngine(
    ICameraControlStore store,
    ICameraControlTransport transport,
    ICameraControlEventPublisher events,
    IDeviceConnectionManager connections,
    TimeProvider timeProvider,
    ILogger<CameraControlEngine>? logger = null) : ICameraControlEngine, ICameraControlDispatcher
{
    private const int MaximumAttempts = 3;
    private const int MaximumHistory = 100;
    private const int MaximumAudit = 500;
    private static readonly TimeSpan AcknowledgementTimeout = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan MaximumQueueAge = TimeSpan.FromMinutes(5);
    private readonly ConcurrentDictionary<Guid, SemaphoreSlim> _deviceGates = new();
    private readonly ConcurrentDictionary<Guid, string> _activeDispatchConnections = new();

    private static readonly Action<ILogger, Guid, Guid, string, Exception?> LogSelected =
        LoggerMessage.Define<Guid, Guid, string>(
            LogLevel.Debug,
            new EventId(10, "CameraControlCommandSelected"),
            "Camera Control selected {CommandId} for {DeviceId} control={Control}");
    private static readonly Action<ILogger, Guid, Guid, string, Exception?> LogConnectionResolved =
        LoggerMessage.Define<Guid, Guid, string>(
            LogLevel.Debug,
            new EventId(11, "CameraControlConnectionResolved"),
            "Camera Control resolved connection for {CommandId} device={DeviceId} connection={ConnectionId}");
    private static readonly Action<ILogger, Guid, Guid, int, Exception?> LogSendAttempt =
        LoggerMessage.Define<Guid, Guid, int>(
            LogLevel.Information,
            new EventId(12, "CameraControlSendAttempted"),
            "Camera Control send attempted {CommandId} device={DeviceId} attempt={Attempt}");
    private static readonly Action<ILogger, Guid, Guid, string, Exception?> LogAcknowledged =
        LoggerMessage.Define<Guid, Guid, string>(
            LogLevel.Information,
            new EventId(13, "CameraControlAcknowledged"),
            "Camera Control acknowledgment received {CommandId} device={DeviceId} result={ResultCode}");
    private static readonly Action<ILogger, Guid, Guid, CameraControlCommandState, string, Exception?> LogFinal =
        LoggerMessage.Define<Guid, Guid, CameraControlCommandState, string>(
            LogLevel.Information,
            new EventId(14, "CameraControlFinalState"),
            "Camera Control final state {CommandId} device={DeviceId} state={State} result={ResultCode}");

    public async Task DispatchPendingAsync(CancellationToken cancellationToken = default)
    {
        var candidates = await store.GetAllAsync(cancellationToken);
        foreach (var candidate in candidates)
        {
            var gate = Gate(candidate.DeviceId);
            await gate.WaitAsync(cancellationToken);
            try
            {
                var device = await store.GetAsync(candidate.DeviceId, cancellationToken);
                if (device is null) continue;
                var now = timeProvider.GetUtcNow();
                var connection = ActiveConnection(device.DeviceId);
                var repaired = RecoverOrphanedExecutions(device, connection?.ConnectionId, now);
                repaired = ExpireQueuedCommands(repaired, now);
                if (!ReferenceEquals(repaired, device))
                {
                    await store.SaveAsync(repaired, cancellationToken);
                    await PublishAsync(repaired.DeviceId, null, "queue_repaired", now, cancellationToken);
                }
                await DispatchNextAsync(repaired, cancellationToken);
            }
            finally
            {
                gate.Release();
            }
        }
    }

    public async Task<CameraControlCenterView> GetAsync(
        Guid deviceId,
        CancellationToken cancellationToken = default)
    {
        var stored = await store.GetAsync(deviceId, cancellationToken);
        var now = timeProvider.GetUtcNow();
        return Map(stored ?? Empty(deviceId, now));
    }

    public async Task<CameraControlCommandView> SubmitAsync(
        Guid deviceId,
        CameraControlCommandRequest request,
        CameraControlActor actor,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ValidateActor(actor);
        var gate = Gate(deviceId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var now = timeProvider.GetUtcNow();
            var device = await store.GetAsync(deviceId, cancellationToken) ?? Empty(deviceId, now);
            var correlationId = string.IsNullOrWhiteSpace(request.CorrelationId)
                ? Guid.NewGuid().ToString("N")
                : request.CorrelationId.Trim();
            try
            {
                var active = ActiveConnection(deviceId);
                if (active is not null
                    && !string.Equals(device.StateConnectionId, active.ConnectionId, StringComparison.Ordinal))
                {
                    throw new ResourceConflictException(
                        "Camera capabilities are stale. Wait for the current device connection to report readiness.");
                }
                ValidateRequest(device, request);
                if (correlationId.Length > 100)
                {
                    throw new DomainValidationException("Correlation id cannot exceed 100 characters.");
                }
            }
            catch (Exception exception) when (exception is DomainValidationException or ResourceConflictException)
            {
                device = AppendAudit(
                    device,
                    null,
                    actor.Subject,
                    "rejected",
                    "capability_validation_failed",
                    correlationId[..Math.Min(correlationId.Length, 100)],
                    now);
                await store.SaveAsync(device, cancellationToken);
                throw;
            }
            var duplicate = device.Commands.FirstOrDefault(command =>
                string.Equals(command.CorrelationId, correlationId, StringComparison.Ordinal));
            if (duplicate is not null)
            {
                if (!string.Equals(duplicate.Control, request.Control, StringComparison.Ordinal))
                {
                    throw new DomainValidationException("A correlation id cannot be reused for another camera control.");
                }
                return duplicate;
            }

            var command = new CameraControlCommandView(
                Guid.NewGuid(),
                deviceId,
                request.Control,
                request.Value,
                CameraControlCommandState.Queued,
                0,
                true,
                true,
                correlationId,
                actor.Subject,
                null,
                now,
                null,
                request.ExpectedVersion);
            device = Append(
                device with { Commands = [command, .. device.Commands], UpdatedAtUtc = now },
                command,
                actor.Subject,
                "queued",
                "accepted",
                now);
            await store.SaveAsync(device, cancellationToken);
            await PublishAsync(deviceId, command.CommandId, "queued", now, cancellationToken);
            device = await DispatchNextAsync(device, cancellationToken);
            return device.Commands.Single(item => item.CommandId == command.CommandId);
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task<CameraControlCommandView> CancelAsync(
        Guid deviceId,
        Guid commandId,
        CameraControlActor actor,
        CancellationToken cancellationToken = default)
    {
        ValidateActor(actor);
        var gate = Gate(deviceId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var device = await store.GetAsync(deviceId, cancellationToken)
                ?? throw new ResourceNotFoundException("CameraControlDevice", deviceId);
            var command = device.Commands.FirstOrDefault(item => item.CommandId == commandId)
                ?? throw new ResourceNotFoundException("CameraControlCommand", commandId);
            if (!command.Cancelable)
            {
                throw new ResourceConflictException("The camera command can no longer be canceled.");
            }
            var now = timeProvider.GetUtcNow();
            if (command.State == CameraControlCommandState.Executing)
            {
                var requested = command with
                {
                    Cancelable = false,
                    ResultCode = "cancel_requested",
                };
                device = Replace(device, requested, now);
                device = Append(device, requested, actor.Subject, "cancel_requested", "sent_to_device", now);
                await store.SaveAsync(device, cancellationToken);
                var active = ActiveConnection(deviceId);
                if (active is not null)
                {
                    await transport.CancelAsync(
                        active.ConnectionId,
                        new CameraControlCancellation(commandId, deviceId),
                        cancellationToken);
                }
                await PublishAsync(deviceId, commandId, "cancel_requested", now, cancellationToken);
                return requested;
            }
            var canceled = command with
            {
                State = CameraControlCommandState.Canceled,
                Cancelable = false,
                Retriable = false,
                ResultCode = "operator_canceled",
                CompletedAtUtc = now,
            };
            device = Replace(device, canceled, now);
            device = Append(device, canceled, actor.Subject, "canceled", "operator_canceled", now);
            await store.SaveAsync(device, cancellationToken);
            await PublishAsync(deviceId, commandId, "canceled", now, cancellationToken);
            await DispatchNextAsync(device, cancellationToken);
            return canceled;
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task<int> ResolveForDeviceRemovalAsync(
        Guid deviceId,
        CameraControlActor actor,
        CancellationToken cancellationToken = default)
    {
        ValidateActor(actor);
        var gate = Gate(deviceId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var device = await store.GetAsync(deviceId, cancellationToken);
            if (device is null)
            {
                return 0;
            }

            var active = device.Commands
                .Where(command => command.State is CameraControlCommandState.Queued
                    or CameraControlCommandState.Retrying
                    or CameraControlCommandState.Executing)
                .ToArray();
            if (active.Length == 0)
            {
                return 0;
            }

            var now = timeProvider.GetUtcNow();
            var connection = ActiveConnection(deviceId);
            foreach (var command in active.Where(command => command.State == CameraControlCommandState.Executing))
            {
                _activeDispatchConnections.TryRemove(command.CommandId, out _);
                if (connection is not null)
                {
                    await transport.CancelAsync(
                        connection.ConnectionId,
                        new CameraControlCancellation(command.CommandId, deviceId),
                        cancellationToken);
                }
            }

            foreach (var command in active)
            {
                var failed = command with
                {
                    State = CameraControlCommandState.Failed,
                    Cancelable = false,
                    Retriable = false,
                    ResultCode = "device_removed",
                    CompletedAtUtc = now,
                };
                device = Replace(device, failed, now);
                device = Append(device, failed, actor.Subject, "failed", "device_removed", now);
            }

            await store.SaveAsync(device, cancellationToken);
            await PublishAsync(deviceId, null, "device_removed", now, cancellationToken);
            return active.Length;
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task ReportAsync(
        Guid authenticatedDeviceId,
        string connectionId,
        CameraControlDeviceReport report,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(report);
        EnsureDeviceConnection(authenticatedDeviceId, connectionId);
        if (report.DeviceId != authenticatedDeviceId)
        {
            throw new AccessDeniedException("Camera Control state does not belong to the authenticated device.");
        }
        ValidateReport(report);
        var gate = Gate(authenticatedDeviceId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var now = timeProvider.GetUtcNow();
            var existing = await store.GetAsync(authenticatedDeviceId, cancellationToken);
            var device = existing is null
                ? Empty(authenticatedDeviceId, now) with
                {
                    DesiredSettings = report.Settings,
                    DeviceState = report,
                    StateConnectionId = connectionId,
                    UpdatedAtUtc = now,
                }
                : existing with { DeviceState = report, StateConnectionId = connectionId, UpdatedAtUtc = now };

            if (existing is not null)
            {
                device = RecoverOrphanedExecutions(device, connectionId, now);
            }

            if (existing is not null
                && report.Settings != device.DesiredSettings
                && !string.Equals(device.LastRestoreConnectionId, connectionId, StringComparison.Ordinal)
                && !device.Commands.Any(command =>
                    command.Control == CameraControlIds.Restore
                    && command.State is CameraControlCommandState.Queued
                        or CameraControlCommandState.Executing
                        or CameraControlCommandState.Retrying))
            {
                var restore = new CameraControlCommandView(
                    Guid.NewGuid(),
                    authenticatedDeviceId,
                    CameraControlIds.Restore,
                    new CameraControlValue(),
                    CameraControlCommandState.Queued,
                    0,
                    true,
                    true,
                    $"restore-{now:yyyyMMddHHmmssfff}",
                    "system",
                    null,
                    now,
                    null);
                device = Append(
                    device with
                    {
                        Commands = [restore, .. device.Commands],
                        LastRestoreConnectionId = connectionId,
                    },
                    restore,
                    "system",
                    "restore_queued",
                    "settings_drift",
                    now);
            }

            await store.SaveAsync(device, cancellationToken);
            await PublishAsync(authenticatedDeviceId, null, "device_state", now, cancellationToken);
            await DispatchNextAsync(device, cancellationToken);
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task CompleteAsync(
        Guid authenticatedDeviceId,
        string connectionId,
        CameraControlCommandResult result,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(result);
        EnsureDeviceConnection(authenticatedDeviceId, connectionId);
        if (result.DeviceId != authenticatedDeviceId || result.DeviceState.DeviceId != authenticatedDeviceId)
        {
            throw new AccessDeniedException("Camera command result does not belong to the authenticated device.");
        }
        var gate = Gate(authenticatedDeviceId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var device = await store.GetAsync(authenticatedDeviceId, cancellationToken)
                ?? throw new ResourceNotFoundException("CameraControlDevice", authenticatedDeviceId);
            var command = device.Commands.FirstOrDefault(item => item.CommandId == result.CommandId)
                ?? throw new ResourceNotFoundException("CameraControlCommand", result.CommandId);
            if (command.State == CameraControlCommandState.Canceled) return;
            if (command.State != CameraControlCommandState.Executing)
            {
                throw new ResourceConflictException("Only an executing camera command can be completed.");
            }
            var now = timeProvider.GetUtcNow();
            _activeDispatchConnections.TryRemove(command.CommandId, out _);
            if (logger is not null)
            {
                LogAcknowledged(logger, command.CommandId, authenticatedDeviceId, SafeCode(result.ResultCode), null);
            }
            CameraControlCommandView completed;
            if (result.Succeeded)
            {
                completed = command with
                {
                    State = CameraControlCommandState.Succeeded,
                    Cancelable = false,
                    Retriable = false,
                    ResultCode = SafeCode(result.ResultCode),
                    CompletedAtUtc = now,
                };
                device = device with
                {
                    DesiredSettings = !IsDesiredCameraSetting(command.Control)
                        ? device.DesiredSettings
                        : Apply(device.DesiredSettings, command.Control, command.Value),
                    DeviceState = result.DeviceState,
                };
            }
            else if (string.Equals(result.ResultCode, "operator_canceled", StringComparison.Ordinal))
            {
                completed = command with
                {
                    State = CameraControlCommandState.Canceled,
                    Cancelable = false,
                    Retriable = false,
                    ResultCode = "operator_canceled",
                    CompletedAtUtc = now,
                };
                device = device with { DeviceState = result.DeviceState };
            }
            else if (result.TransientFailure && command.Attempts < MaximumAttempts)
            {
                completed = command with
                {
                    State = CameraControlCommandState.Retrying,
                    Cancelable = true,
                    Retriable = true,
                    ResultCode = SafeCode(result.ResultCode),
                };
                device = device with { DeviceState = result.DeviceState };
            }
            else
            {
                completed = command with
                {
                    State = CameraControlCommandState.Failed,
                    Cancelable = false,
                    Retriable = false,
                    ResultCode = SafeCode(result.ResultCode),
                    CompletedAtUtc = now,
                };
                device = device with { DeviceState = result.DeviceState };
            }
            device = Replace(device with { StateConnectionId = connectionId }, completed, now);
            device = Append(
                device,
                completed,
                "device",
                result.Succeeded ? "completed" : completed.State == CameraControlCommandState.Canceled ? "canceled" : result.TransientFailure ? "retrying" : "failed",
                completed.ResultCode ?? "unknown",
                now);
            await store.SaveAsync(device, cancellationToken);
            await PublishAsync(authenticatedDeviceId, command.CommandId, "completed", now, cancellationToken);
            if (logger is not null && completed.State is CameraControlCommandState.Succeeded
                    or CameraControlCommandState.Failed
                    or CameraControlCommandState.Canceled)
            {
                LogFinal(
                    logger,
                    command.CommandId,
                    authenticatedDeviceId,
                    completed.State,
                    completed.ResultCode ?? "unknown",
                    null);
            }
            await DispatchNextAsync(device, cancellationToken);
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task<StoredCameraControlDevice> DispatchNextAsync(
        StoredCameraControlDevice device,
        CancellationToken cancellationToken)
    {
        if (device.Commands.Any(command => command.State == CameraControlCommandState.Executing)) return device;
        var next = device.Commands
            .Where(command => command.State is CameraControlCommandState.Queued or CameraControlCommandState.Retrying)
            .OrderBy(command => command.CreatedAtUtc)
            .FirstOrDefault();
        var connection = ActiveConnection(device.DeviceId);
        if (next is null) return device;
        if (logger is not null)
        {
            LogSelected(logger, next.CommandId, device.DeviceId, next.Control, null);
        }
        if (connection is null)
        {
            if (!string.Equals(next.ResultCode, "waiting_for_device", StringComparison.Ordinal))
            {
                var waiting = next with { ResultCode = "waiting_for_device" };
                device = Replace(device, waiting, timeProvider.GetUtcNow());
                await store.SaveAsync(device, cancellationToken);
                await PublishAsync(
                    device.DeviceId,
                    waiting.CommandId,
                    "waiting_for_device",
                    timeProvider.GetUtcNow(),
                    cancellationToken);
            }
            return device;
        }
        if (logger is not null)
        {
            LogConnectionResolved(logger, next.CommandId, device.DeviceId, connection.ConnectionId, null);
        }

        var now = timeProvider.GetUtcNow();
        var executing = next with
        {
            State = CameraControlCommandState.Executing,
            Attempts = next.Attempts + 1,
            Cancelable = true,
            Retriable = next.Attempts + 1 < MaximumAttempts,
            ResultCode = null,
        };
        device = Replace(device, executing, now);
        device = Append(device, executing, "system", "dispatched", "attempted", now);
        await store.SaveAsync(device, cancellationToken);
        if (logger is not null)
        {
            LogSendAttempt(logger, executing.CommandId, device.DeviceId, executing.Attempts, null);
        }
        var desired = !IsDesiredCameraSetting(executing.Control)
            ? device.DesiredSettings
            : Apply(device.DesiredSettings, executing.Control, executing.Value);
        var dispatched = await transport.DispatchAsync(
            connection.ConnectionId,
            new CameraControlCommandEnvelope(
                executing.CommandId,
                executing.DeviceId,
                executing.Control,
                executing.Value,
                desired,
                executing.Attempts,
                executing.CreatedAtUtc,
                executing.ExpectedVersion),
            cancellationToken);
        if (dispatched)
        {
            _activeDispatchConnections[executing.CommandId] = connection.ConnectionId;
            await PublishAsync(device.DeviceId, executing.CommandId, "executing", now, cancellationToken);
            return device;
        }

        _activeDispatchConnections.TryRemove(executing.CommandId, out _);

        var retry = executing with
        {
            State = executing.Attempts < MaximumAttempts
                ? CameraControlCommandState.Retrying
                : CameraControlCommandState.Failed,
            Cancelable = executing.Attempts < MaximumAttempts,
            Retriable = executing.Attempts < MaximumAttempts,
            ResultCode = "device_offline",
            CompletedAtUtc = executing.Attempts < MaximumAttempts ? null : now,
        };
        device = Replace(device, retry, now);
        device = Append(device, retry, "system", "dispatch_failed", "device_offline", now);
        await store.SaveAsync(device, cancellationToken);
        await PublishAsync(device.DeviceId, retry.CommandId, "dispatch_failed", now, cancellationToken);
        return device;
    }

    private StoredCameraControlDevice RecoverOrphanedExecutions(
        StoredCameraControlDevice device,
        string? connectionId,
        DateTimeOffset now)
    {
        foreach (var command in device.Commands.Where(command =>
                     command.State == CameraControlCommandState.Executing
                     && IsExecutionOrphaned(device, command, connectionId, now)))
        {
            _activeDispatchConnections.TryRemove(command.CommandId, out _);
            var cancelRequested = string.Equals(command.ResultCode, "cancel_requested", StringComparison.Ordinal);
            var canRetry = !cancelRequested && command.Attempts < MaximumAttempts;
            var recovered = command with
            {
                State = cancelRequested
                    ? CameraControlCommandState.Canceled
                    : canRetry
                        ? CameraControlCommandState.Retrying
                        : CameraControlCommandState.Failed,
                Cancelable = canRetry,
                Retriable = canRetry,
                ResultCode = cancelRequested ? "operator_canceled" : "connection_interrupted",
                CompletedAtUtc = canRetry ? null : now,
            };
            device = Replace(device, recovered, now);
            device = Append(
                device,
                recovered,
                "system",
                cancelRequested ? "canceled" : canRetry ? "recovered" : "failed",
                recovered.ResultCode,
                now);
        }
        return device;
    }

    private bool IsExecutionOrphaned(
        StoredCameraControlDevice device,
        CameraControlCommandView command,
        string? connectionId,
        DateTimeOffset now)
    {
        if (!_activeDispatchConnections.TryGetValue(command.CommandId, out var ownerConnection)
            || !string.Equals(ownerConnection, connectionId, StringComparison.Ordinal))
        {
            return true;
        }
        var dispatchedAt = device.Audit
            .Where(entry => entry.CommandId == command.CommandId && entry.Action == "dispatched")
            .Select(entry => (DateTimeOffset?)entry.OccurredAtUtc)
            .Max();
        return dispatchedAt is null || now - dispatchedAt >= AcknowledgementTimeout;
    }

    private StoredCameraControlDevice ExpireQueuedCommands(
        StoredCameraControlDevice device,
        DateTimeOffset now)
    {
        foreach (var command in device.Commands.Where(command =>
                     command.State is CameraControlCommandState.Queued or CameraControlCommandState.Retrying
                     && now - command.CreatedAtUtc >= MaximumQueueAge))
        {
            var expired = command with
            {
                State = CameraControlCommandState.Failed,
                Cancelable = false,
                Retriable = false,
                ResultCode = "queue_expired",
                CompletedAtUtc = now,
            };
            device = Replace(device, expired, now);
            device = Append(device, expired, "system", "failed", "queue_expired", now);
            if (logger is not null)
            {
                LogFinal(logger, expired.CommandId, device.DeviceId, expired.State, "queue_expired", null);
            }
        }
        return device;
    }

    private static void ValidateRequest(StoredCameraControlDevice device, CameraControlCommandRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Control) || request.Control == CameraControlIds.Restore)
        {
            throw new DomainValidationException("The camera control is invalid.");
        }
        if (MotionSettingIds.IsMotionSetting(request.Control))
        {
            ValidateMotionRequest(device, request);
            return;
        }
        var descriptor = device.DeviceState?.Capabilities.FirstOrDefault(capability =>
            string.Equals(capability.Id, request.Control, StringComparison.Ordinal));
        if (descriptor is null)
        {
            throw new ResourceConflictException("Camera capabilities are not available. Wait for the device to reconnect.");
        }
        if (!descriptor.Supported || !descriptor.Writable)
        {
            throw new DomainValidationException(descriptor.Reason ?? "This camera control is read-only or unsupported.");
        }
        _ = Apply(device.DesiredSettings, request.Control, request.Value);
        if (request.Value.Number is { } number)
        {
            if (descriptor.Minimum is { } minimum && number < minimum
                || descriptor.Maximum is { } maximum && number > maximum)
            {
                throw new DomainValidationException($"{request.Control} is outside the supported range.");
            }
        }
        if (request.Value.Text is { } text
            && descriptor.AllowedValues is { Count: > 0 } allowed
            && !allowed.Contains(text, StringComparer.OrdinalIgnoreCase))
        {
            throw new DomainValidationException($"{request.Control} is not supported by this camera.");
        }
    }

    private static void ValidateMotionRequest(
        StoredCameraControlDevice device,
        CameraControlCommandRequest request)
    {
        var report = device.DeviceState?.MotionSettings
            ?? throw new ResourceConflictException("Motion settings are unavailable. Wait for the device to reconnect.");
        var descriptor = report.Capabilities.FirstOrDefault(capability =>
            string.Equals(capability.Id, request.Control, StringComparison.Ordinal));
        if (descriptor is null)
        {
            throw new ResourceConflictException("This Motion setting is not reported by the device.");
        }
        if (!descriptor.Supported || !descriptor.Writable)
        {
            throw new DomainValidationException(descriptor.Reason ?? "This Motion setting is read-only or unsupported.");
        }
        if (request.ExpectedVersion is null)
        {
            throw new DomainValidationException("The Motion settings version is required.");
        }
        if (request.ExpectedVersion != report.Version)
        {
            throw new ResourceConflictException("Motion settings changed on the device. Refresh before applying this value.");
        }
        var populated = (request.Value.Boolean is null ? 0 : 1)
            + (request.Value.Number is null ? 0 : 1)
            + (request.Value.Text is null ? 0 : 1)
            + (request.Value.DateTimeOverlay is null ? 0 : 1);
        if (populated != 1)
        {
            throw new DomainValidationException("The Motion setting value type is invalid.");
        }
        var expectedBoolean = descriptor.CurrentValue.Boolean is not null;
        var expectedNumber = descriptor.CurrentValue.Number is not null;
        var expectedText = descriptor.CurrentValue.Text is not null;
        if (expectedBoolean != (request.Value.Boolean is not null)
            || expectedNumber != (request.Value.Number is not null)
            || expectedText != (request.Value.Text is not null))
        {
            throw new DomainValidationException("The Motion setting value type is invalid.");
        }
        if (request.Value.Number is { } number)
        {
            if (!double.IsFinite(number)
                || descriptor.Minimum is { } minimum && number < minimum
                || descriptor.Maximum is { } maximum && number > maximum
                || descriptor.Step is { } step && step > 0
                    && Math.Abs((number - (descriptor.Minimum ?? 0)) / step
                        - Math.Round((number - (descriptor.Minimum ?? 0)) / step)) > 0.000001)
            {
                throw new DomainValidationException("The Motion setting is outside the supported range.");
            }
            if (descriptor.AllowedValues is { Count: > 0 } allowed
                && !allowed.Contains(number.ToString(CultureInfo.InvariantCulture), StringComparer.Ordinal))
            {
                throw new DomainValidationException("The Motion setting value is not supported by this device.");
            }
        }
        if (request.Value.Text is { } text
            && descriptor.AllowedValues is { Count: > 0 } textAllowed
            && !textAllowed.Contains(text, StringComparer.OrdinalIgnoreCase))
        {
            throw new DomainValidationException("The Motion setting value is not supported by this device.");
        }
    }

    private static CameraControlSettings Apply(
        CameraControlSettings settings,
        string control,
        CameraControlValue value) => control switch
    {
        CameraControlIds.Lens => settings with { Lens = RequiredText(value, control) },
        CameraControlIds.Zoom => settings with { Zoom = RequiredNumber(value, control) },
        CameraControlIds.Torch => settings with { Torch = RequiredBoolean(value, control) },
        CameraControlIds.ExposureCompensation => settings with { ExposureCompensation = RequiredInteger(value, control) },
        CameraControlIds.Preview => settings with { Preview = RequiredText(value, control) },
        CameraControlIds.FramesPerSecond => settings with { FramesPerSecond = RequiredInteger(value, control) },
        CameraControlIds.Resolution => settings with { Resolution = RequiredText(value, control) },
        CameraControlIds.Bitrate => settings with { Bitrate = RequiredInteger(value, control) },
        CameraControlIds.Quality => settings with { Quality = RequiredText(value, control) },
        CameraControlIds.NightProfile => settings with { NightProfile = RequiredText(value, control) },
        CameraControlIds.DateTimeOverlay => settings with { DateTimeOverlay = RequiredDateTimeOverlay(value) },
        CameraControlIds.Recording => RequiredRecordingAction(settings, value),
        CameraControlIds.Restore => settings,
        _ => throw new DomainValidationException("The camera control is unknown."),
    };

    private static bool IsDesiredCameraSetting(string control) =>
        control is not CameraControlIds.Restore and not CameraControlIds.Recording
        && !MotionSettingIds.IsMotionSetting(control);

    private static string RequiredText(CameraControlValue value, string control) =>
        !string.IsNullOrWhiteSpace(value.Text) && value.Boolean is null && value.Number is null
            && value.DateTimeOverlay is null
            ? value.Text.Trim().ToLowerInvariant()
            : throw new DomainValidationException($"{control} requires a text value.");

    private static double RequiredNumber(CameraControlValue value, string control) =>
        value.Number is { } number && double.IsFinite(number) && value.Boolean is null && value.Text is null
            && value.DateTimeOverlay is null
            ? number
            : throw new DomainValidationException($"{control} requires a numeric value.");

    private static int RequiredInteger(CameraControlValue value, string control)
    {
        var number = RequiredNumber(value, control);
        if (number != Math.Truncate(number) || number is < int.MinValue or > int.MaxValue)
        {
            throw new DomainValidationException($"{control} requires a whole number in the supported range.");
        }
        return (int)number;
    }

    private static bool RequiredBoolean(CameraControlValue value, string control) =>
        value.Boolean is { } boolean && value.Number is null && value.Text is null && value.DateTimeOverlay is null
            ? boolean
            : throw new DomainValidationException($"{control} requires a boolean value.");

    private static DateTimeOverlayConfiguration RequiredDateTimeOverlay(CameraControlValue value)
    {
        if (value.DateTimeOverlay is not { } overlay
            || value.Boolean is not null
            || value.Number is not null
            || value.Text is not null)
        {
            throw new DomainValidationException("dateTimeOverlay requires an overlay configuration value.");
        }
        if (!DateTimeOverlayPositions.All.Contains(overlay.Position, StringComparer.Ordinal)
            || overlay.Enabled && !overlay.DateEnabled && !overlay.TimeEnabled)
        {
            throw new DomainValidationException("The Date/Time Overlay configuration is invalid.");
        }
        return overlay;
    }

    private static CameraControlSettings RequiredRecordingAction(
        CameraControlSettings settings,
        CameraControlValue value)
    {
        var action = RequiredText(value, CameraControlIds.Recording);
        return action is CameraControlValues.Start or CameraControlValues.Stop
            ? settings
            : throw new DomainValidationException("Recording requires start or stop.");
    }

    private static void ValidateReport(CameraControlDeviceReport report)
    {
        if (report.Capabilities.Count is 0 or > 40
            || report.Capabilities.Select(capability => capability.Id).Distinct(StringComparer.Ordinal).Count()
                != report.Capabilities.Count
            || report.Capabilities.Any(capability => string.IsNullOrWhiteSpace(capability.Id)
                || capability.AllowedValues?.Count > 100))
        {
            throw new DomainValidationException("The camera capability report is invalid.");
        }
        if (report.MotionSettings is { } motion
            && (motion.Version < 1
                || motion.Capabilities.Count is 0 or > 20
                || motion.Capabilities.Select(capability => capability.Id).Distinct(StringComparer.Ordinal).Count()
                    != motion.Capabilities.Count
                || !MotionSettingIds.UserConfigurable.All(id => motion.Capabilities.Any(capability =>
                    string.Equals(capability.Id, id, StringComparison.Ordinal)))
                || motion.Capabilities.Any(capability => string.IsNullOrWhiteSpace(capability.Id)
                    || capability.AllowedValues?.Count > 20
                    || (capability.CurrentValue.Boolean is null ? 0 : 1)
                        + (capability.CurrentValue.Number is null ? 0 : 1)
                        + (capability.CurrentValue.Text is null ? 0 : 1) != 1)))
        {
            throw new DomainValidationException("The Motion settings capability report is invalid.");
        }
    }

    private void EnsureDeviceConnection(Guid deviceId, string connectionId)
    {
        var connection = ActiveConnection(deviceId);
        if (connection is null
            || !string.Equals(connection.ConnectionId, connectionId, StringComparison.Ordinal))
        {
            throw new AccessDeniedException("Camera Control requires the active authenticated device connection.");
        }
    }

    private DeviceConnectionStatus? ActiveConnection(Guid deviceId)
    {
        var connection = connections.GetStatus(DeviceId.From(deviceId));
        return connection?.State == DeviceTransportState.Connected ? connection : null;
    }

    private SemaphoreSlim Gate(Guid deviceId) => _deviceGates.GetOrAdd(deviceId, _ => new SemaphoreSlim(1, 1));

    private static StoredCameraControlDevice Empty(Guid deviceId, DateTimeOffset now) => new(
        deviceId,
        CameraControlSettings.Default,
        null,
        [],
        [],
        now);

    private CameraControlCenterView Map(StoredCameraControlDevice device)
    {
        var active = ActiveConnection(device.DeviceId);
        var currentState = active is not null
            && string.Equals(device.StateConnectionId, active.ConnectionId, StringComparison.Ordinal)
                ? device.DeviceState
                : null;
        return new CameraControlCenterView(
            device.DeviceId,
            active is not null,
            device.DesiredSettings,
            currentState,
            device.Commands.OrderByDescending(command => command.CreatedAtUtc).Take(20).ToArray(),
            device.UpdatedAtUtc);
    }

    private static StoredCameraControlDevice Replace(
        StoredCameraControlDevice device,
        CameraControlCommandView command,
        DateTimeOffset now) => device with
    {
        Commands = device.Commands.Select(item => item.CommandId == command.CommandId ? command : item).ToArray(),
        UpdatedAtUtc = now,
    };

    private static StoredCameraControlDevice Append(
        StoredCameraControlDevice device,
        CameraControlCommandView command,
        string actor,
        string action,
        string outcome,
        DateTimeOffset now) => device with
    {
        Commands = device.Commands.Take(MaximumHistory).ToArray(),
        Audit =
        [
            new CameraControlAuditEntry(
                Guid.NewGuid(),
                device.DeviceId,
                command.CommandId,
                actor,
                action,
                outcome,
                command.CorrelationId,
                now),
            .. device.Audit.Take(MaximumAudit - 1),
        ],
        UpdatedAtUtc = now,
    };

    private static StoredCameraControlDevice AppendAudit(
        StoredCameraControlDevice device,
        Guid? commandId,
        string actor,
        string action,
        string outcome,
        string correlationId,
        DateTimeOffset now) => device with
    {
        Audit =
        [
            new CameraControlAuditEntry(
                Guid.NewGuid(),
                device.DeviceId,
                commandId,
                actor,
                action,
                outcome,
                correlationId,
                now),
            .. device.Audit.Take(MaximumAudit - 1),
        ],
        UpdatedAtUtc = now,
    };

    private Task PublishAsync(
        Guid deviceId,
        Guid? commandId,
        string reason,
        DateTimeOffset now,
        CancellationToken cancellationToken) =>
        events.ChangedAsync(new CameraControlUpdate(deviceId, commandId, reason, now), cancellationToken);

    private static void ValidateActor(CameraControlActor actor)
    {
        ArgumentNullException.ThrowIfNull(actor);
        if (string.IsNullOrWhiteSpace(actor.Subject) || actor.Subject.Length > 200)
        {
            throw new AccessDeniedException("The authenticated operator identity is unavailable.");
        }
    }

    private static string SafeCode(string value) =>
        string.IsNullOrWhiteSpace(value) ? "unknown" : value.Trim()[..Math.Min(value.Trim().Length, 100)];
}
