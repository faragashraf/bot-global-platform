using FluentValidation;
using SentriCam.Application.Registration;
using SentriCam.Contracts.Registration;
using SentriCam.Domain.Devices;
using SentriCam.Tests.TestDoubles;
using SentriCam.Application.Common;

namespace SentriCam.Tests.Application;

public sealed class RegistrationServiceTests
{
    [Fact]
    public async Task InitialRegistrationCreatesDeviceAuditAndAccessToken()
    {
        var devices = new FakeDeviceRepository();
        var registrations = new FakeRegistrationRepository();
        var unitOfWork = new FakeUnitOfWork();
        var service = CreateService(devices, registrations, unitOfWork);

        var result = await service.RegisterAsync(
            DeviceTestFactory.CreateRegistrationRequest(),
            TestContext.Current.CancellationToken);

        Assert.True(result.IsNewDevice);
        Assert.Equal("stub-device-token", result.AccessToken);
        Assert.Equal(DeviceTestFactory.Now.AddHours(1), result.AccessTokenExpiresAtUtc);
        Assert.Null(result.RefreshToken);
        Assert.Null(result.RefreshTokenExpirationUtc);
        Assert.Equal(DeviceTestFactory.Now, result.ServerUtcNow);
        Assert.Equal("installation-001", result.InstallationId);
        Assert.Equal(RegistrationStates.Registered, result.RegistrationState);
        Assert.Equal(RegistrationSchema.CurrentVersion, result.RegistrationSchemaVersion);
        Assert.Equal(result.DeviceId, Assert.Single(devices.Devices).Id.Value);
        Assert.Equal(DeviceRegistrationKind.Initial, Assert.Single(registrations.Registrations).Kind);
        Assert.Equal(1, unitOfWork.SaveCount);
    }

    [Fact]
    public async Task RepeatRegistrationReusesDeviceAndRecordsRenewal()
    {
        var devices = new FakeDeviceRepository();
        var existing = DeviceTestFactory.CreateDevice();
        devices.Add(existing);
        var registrations = new FakeRegistrationRepository();
        var unitOfWork = new FakeUnitOfWork();
        var service = CreateService(devices, registrations, unitOfWork);
        var request = DeviceTestFactory.CreateRegistrationRequest() with { DisplayName = "Updated Camera" };

        var result = await service.RegisterAsync(request, TestContext.Current.CancellationToken);

        Assert.False(result.IsNewDevice);
        Assert.Equal(existing.Id.Value, result.DeviceId);
        Assert.Equal("Updated Camera", existing.DisplayName);
        Assert.Single(devices.Devices);
        Assert.Equal(DeviceRegistrationKind.Renewal, Assert.Single(registrations.Registrations).Kind);
    }

    [Fact]
    public async Task IdempotentRegistrationAcknowledgesSameInstallationAndServerDevice()
    {
        var devices = new FakeDeviceRepository();
        var registrations = new FakeRegistrationRepository();
        var unitOfWork = new FakeUnitOfWork();
        var service = CreateService(devices, registrations, unitOfWork);
        var request = DeviceTestFactory.CreateRegistrationRequest();

        var first = await service.RegisterAsync(request, TestContext.Current.CancellationToken);
        var second = await service.RegisterAsync(request, TestContext.Current.CancellationToken);

        Assert.Equal(first.DeviceId, second.DeviceId);
        Assert.True(first.IsNewDevice);
        Assert.False(second.IsNewDevice);
        Assert.Equal(request.Identity.InstallationId, second.InstallationId);
        Assert.Single(devices.Devices);
        Assert.Equal(2, registrations.Registrations.Count);
    }

    [Fact]
    public async Task InvalidRegistrationDoesNotWriteAnything()
    {
        var devices = new FakeDeviceRepository();
        var registrations = new FakeRegistrationRepository();
        var unitOfWork = new FakeUnitOfWork();
        var service = CreateService(devices, registrations, unitOfWork);
        var invalid = DeviceTestFactory.CreateRegistrationRequest() with { DisplayName = string.Empty };

        await Assert.ThrowsAsync<ValidationException>(() =>
            service.RegisterAsync(invalid, TestContext.Current.CancellationToken));

        Assert.Empty(devices.Devices);
        Assert.Empty(registrations.Registrations);
        Assert.Equal(0, unitOfWork.SaveCount);
    }

    [Fact]
    public async Task RemovedInstallationRequiresPairingAndReclaimsTheSameDeviceIdentity()
    {
        var devices = new FakeDeviceRepository();
        var existing = DeviceTestFactory.CreateDevice();
        existing.Unpair(DeviceTestFactory.Now.AddMinutes(1));
        devices.Add(existing);
        var registrations = new FakeRegistrationRepository();
        var service = CreateService(devices, registrations, new FakeUnitOfWork());
        var request = DeviceTestFactory.CreateRegistrationRequest();

        await Assert.ThrowsAsync<ResourceConflictException>(() =>
            service.RegisterAsync(request, TestContext.Current.CancellationToken));
        var repaired = await service.RegisterFromPairingAsync(
            request,
            TestContext.Current.CancellationToken);

        Assert.Equal(existing.Id.Value, repaired.DeviceId);
        Assert.False(repaired.IsNewDevice);
        Assert.True(existing.IsEnabled);
        Assert.Equal(DeviceStatus.Registered, existing.Status);
        Assert.Single(devices.Devices);
        Assert.Equal(DeviceRegistrationKind.Renewal, Assert.Single(registrations.Registrations).Kind);
    }

    [Fact]
    public async Task CredentialValidatorAcceptsOnlyLatestEnabledRegistration()
    {
        var devices = new FakeDeviceRepository();
        var registrations = new FakeRegistrationRepository();
        var clock = new MutableTimeProvider(DeviceTestFactory.Now);
        var service = CreateService(devices, registrations, new FakeUnitOfWork(), clock);
        var request = DeviceTestFactory.CreateRegistrationRequest();
        var first = await service.RegisterAsync(request, TestContext.Current.CancellationToken);
        var device = Assert.Single(devices.Devices);
        var firstRegistration = Assert.Single(registrations.Registrations);
        var validator = new DeviceCredentialValidator(devices, registrations);

        Assert.True(await validator.IsCurrentAsync(
            device.Id,
            firstRegistration.Id,
            TestContext.Current.CancellationToken));
        clock.Value = DeviceTestFactory.Now.AddMinutes(1);
        device.Unpair(clock.GetUtcNow());
        Assert.False(await validator.IsCurrentAsync(
            device.Id,
            firstRegistration.Id,
            TestContext.Current.CancellationToken));

        var repaired = await service.RegisterFromPairingAsync(request, TestContext.Current.CancellationToken);
        var latest = registrations.Registrations.Last();
        Assert.Equal(first.DeviceId, repaired.DeviceId);
        Assert.False(await validator.IsCurrentAsync(device.Id, firstRegistration.Id, TestContext.Current.CancellationToken));
        Assert.True(await validator.IsCurrentAsync(device.Id, latest.Id, TestContext.Current.CancellationToken));
    }

    private static RegistrationService CreateService(
        FakeDeviceRepository devices,
        FakeRegistrationRepository registrations,
        FakeUnitOfWork unitOfWork,
        TimeProvider? timeProvider = null) =>
        new(
            devices,
            registrations,
            unitOfWork,
            new FakeTokenIssuer(),
            new RegistrationRequestValidator(),
            timeProvider ?? new FixedTimeProvider(DeviceTestFactory.Now));
}
