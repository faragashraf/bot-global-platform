using SentriCam.Application.Commands;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Registration;
using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class CommandHandlerTests
{
    [Fact]
    public async Task RegisterDeviceHandlerForwardsToRegistrationService()
    {
        var expected = new RegistrationResult(
            Guid.NewGuid(),
            true,
            "token",
            DeviceTestFactory.Now.AddHours(1),
            DeviceTestFactory.Now);
        var registrationService = new FakeRegistrationService(expected);
        var handler = new RegisterDeviceCommandHandler(registrationService);
        var request = DeviceTestFactory.CreateRegistrationRequest();

        var result = await handler.HandleAsync(
            new RegisterDevice(request),
            TestContext.Current.CancellationToken);

        Assert.Equal(expected, result);
        Assert.Equal(request, registrationService.LastCall!.Request);
    }

    [Fact]
    public async Task PingHandlerMarksDeviceSeenAndPersists()
    {
        var devices = new FakeDeviceRepository();
        var device = DeviceTestFactory.CreateDevice();
        devices.Add(device);
        var unitOfWork = new FakeUnitOfWork();
        var pingAt = DeviceTestFactory.Now.AddMinutes(2);
        var handler = new PingCommandHandler(
            devices,
            unitOfWork,
            new FixedTimeProvider(pingAt),
            new FakeDeviceRequestAuthorizer(device.Id));

        var result = await handler.HandleAsync(
            new Ping(device.Id.Value, DeviceTestFactory.Now),
            TestContext.Current.CancellationToken);

        Assert.Equal(pingAt, result.ServerTimeUtc);
        Assert.Equal(DeviceStatus.Online, device.Status);
        Assert.Equal(1, unitOfWork.SaveCount);
    }

    [Fact]
    public async Task GetStatusHandlerMapsDomainStatusWithoutWriting()
    {
        var devices = new FakeDeviceRepository();
        var device = DeviceTestFactory.CreateDevice();
        device.ChangeStatus(DeviceStatus.Monitoring, DeviceTestFactory.Now.AddMinutes(1));
        devices.Add(device);
        var handler = new GetStatusCommandHandler(
            devices,
            new FakeDeviceRequestAuthorizer(device.Id));

        var result = await handler.HandleAsync(
            new GetStatus(device.Id.Value),
            TestContext.Current.CancellationToken);

        Assert.Equal(DeviceOperationalStatus.Monitoring, result.Status);
        Assert.True(result.IsEnabled);
    }

    [Fact]
    public async Task EveryControlContractCanBeQueuedAtTheApplicationBoundary()
    {
        var commands = new IDeviceControlCommand[]
        {
            new StartMonitoring(Guid.Empty),
            new StopMonitoring(Guid.Empty),
            new StartRecording(Guid.Empty),
            new StopRecording(Guid.Empty),
            new RestartMonitoring(Guid.Empty),
            new PingDevice(Guid.Empty),
            new RefreshDeviceStatus(Guid.Empty),
        };

        foreach (var commandTemplate in commands)
        {
            var devices = new FakeDeviceRepository();
            var device = DeviceTestFactory.CreateDevice(Guid.NewGuid().ToString("N"));
            devices.Add(device);
            var commandsRepository = new FakeCommandRepository();
            var unitOfWork = new FakeUnitOfWork();
            var handler = new DeviceControlCommandHandler(
                devices,
                commandsRepository,
                unitOfWork,
                new FixedTimeProvider(DeviceTestFactory.Now),
                new FakeDeviceRequestAuthorizer(device.Id));
            var command = WithDeviceId(commandTemplate, device.Id.Value);

            var result = await handler.HandleAsync(command, TestContext.Current.CancellationToken);

            Assert.Equal(command.CommandType, result.CommandType);
            Assert.NotEqual(Guid.Empty, result.CommandId);
            Assert.Single(commandsRepository.Commands);
            Assert.Equal(1, unitOfWork.SaveCount);
        }
    }

    [Fact]
    public async Task RepeatedControlCommandWithSameCorrelationIdIsIdempotent()
    {
        var devices = new FakeDeviceRepository();
        var device = DeviceTestFactory.CreateDevice();
        devices.Add(device);
        var commandsRepository = new FakeCommandRepository();
        var unitOfWork = new FakeUnitOfWork();
        var handler = new DeviceControlCommandHandler(
            devices,
            commandsRepository,
            unitOfWork,
            new FixedTimeProvider(DeviceTestFactory.Now),
            new FakeDeviceRequestAuthorizer(device.Id));
        var command = new StartMonitoring(device.Id.Value, "android-retry-42");

        var first = await handler.HandleAsync(command, TestContext.Current.CancellationToken);
        var retry = await handler.HandleAsync(command, TestContext.Current.CancellationToken);

        Assert.Equal(first, retry);
        Assert.Single(commandsRepository.Commands);
        Assert.Equal(1, unitOfWork.SaveCount);
    }

    [Fact]
    public async Task CorrelationIdCannotBeReusedForDifferentCommandType()
    {
        var devices = new FakeDeviceRepository();
        var device = DeviceTestFactory.CreateDevice();
        devices.Add(device);
        var commandsRepository = new FakeCommandRepository();
        var unitOfWork = new FakeUnitOfWork();
        var handler = new DeviceControlCommandHandler(
            devices,
            commandsRepository,
            unitOfWork,
            new FixedTimeProvider(DeviceTestFactory.Now),
            new FakeDeviceRequestAuthorizer(device.Id));

        await handler.HandleAsync(
            new StartMonitoring(device.Id.Value, "same-correlation"),
            TestContext.Current.CancellationToken);

        await Assert.ThrowsAsync<DomainValidationException>(() => handler.HandleAsync(
            new StopMonitoring(device.Id.Value, "same-correlation"),
            TestContext.Current.CancellationToken));
        Assert.Single(commandsRepository.Commands);
        Assert.Equal(1, unitOfWork.SaveCount);
    }

    [Fact]
    public async Task ControlCommandRequiresDeviceAccessAtApplicationBoundary()
    {
        var devices = new FakeDeviceRepository();
        var device = DeviceTestFactory.CreateDevice();
        devices.Add(device);
        var commandsRepository = new FakeCommandRepository();
        var unitOfWork = new FakeUnitOfWork();
        var handler = new DeviceControlCommandHandler(
            devices,
            commandsRepository,
            unitOfWork,
            new FixedTimeProvider(DeviceTestFactory.Now),
            new FakeDeviceRequestAuthorizer(DeviceId.New()));

        await Assert.ThrowsAsync<InvalidOperationException>(() => handler.HandleAsync(
            new StartMonitoring(device.Id.Value, "not-authorized"),
            TestContext.Current.CancellationToken));
        Assert.Empty(commandsRepository.Commands);
        Assert.Equal(0, unitOfWork.SaveCount);
    }

    private static IDeviceControlCommand WithDeviceId(IDeviceControlCommand command, Guid deviceId) =>
        command switch
        {
            StartMonitoring => new StartMonitoring(deviceId),
            StopMonitoring => new StopMonitoring(deviceId),
            StartRecording => new StartRecording(deviceId),
            StopRecording => new StopRecording(deviceId),
            RestartMonitoring => new RestartMonitoring(deviceId),
            PingDevice => new PingDevice(deviceId),
            RefreshDeviceStatus => new RefreshDeviceStatus(deviceId),
            _ => throw new ArgumentOutOfRangeException(nameof(command)),
        };
}
