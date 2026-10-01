using System.Text.Json;
using SentriCam.Contracts.Registration;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Contracts;

public sealed class RegistrationResultContractTests
{
    [Fact]
    public void LegacyConstructorRemainsCompatible()
    {
        var result = new RegistrationResult(
            Guid.NewGuid(),
            true,
            "access-token",
            DeviceTestFactory.Now.AddHours(1),
            DeviceTestFactory.Now);

        Assert.Null(result.RefreshToken);
        Assert.Null(result.RefreshTokenExpirationUtc);
        Assert.Equal(default, result.ServerUtcNow);
        Assert.Null(result.InstallationId);
        Assert.Equal(RegistrationStates.Registered, result.RegistrationState);
        Assert.Equal(RegistrationSchema.CurrentVersion, result.RegistrationSchemaVersion);
    }

    [Fact]
    public void ContractExposesFutureRefreshAndServerTimeFields()
    {
        var refreshExpiry = DeviceTestFactory.Now.AddDays(30);
        var result = new RegistrationResult(
            Guid.NewGuid(),
            false,
            "access-token",
            DeviceTestFactory.Now.AddHours(1),
            DeviceTestFactory.Now,
            "refresh-token",
            refreshExpiry,
            DeviceTestFactory.Now,
            "installation-001",
            RegistrationStates.Registered,
            RegistrationSchema.CurrentVersion);

        var json = JsonSerializer.Serialize(result);

        Assert.Equal("refresh-token", result.RefreshToken);
        Assert.Equal(refreshExpiry, result.RefreshTokenExpirationUtc);
        Assert.Equal(DeviceTestFactory.Now, result.ServerUtcNow);
        Assert.Equal("installation-001", result.InstallationId);
        Assert.Equal(RegistrationSchema.CurrentVersion, result.RegistrationSchemaVersion);
        Assert.Contains("RefreshTokenExpirationUtc", json, StringComparison.Ordinal);
        Assert.Contains("ServerUtcNow", json, StringComparison.Ordinal);
        Assert.Contains("InstallationId", json, StringComparison.Ordinal);
        Assert.Contains("RegistrationSchemaVersion", json, StringComparison.Ordinal);
    }
}
