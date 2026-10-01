using SentriCam.Application.Registration;
using SentriCam.Contracts.Registration;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Application;

public sealed class RegistrationRequestValidatorTests
{
    private readonly RegistrationRequestValidator _validator = new();

    [Fact]
    public async Task ValidRegistrationPassesValidation()
    {
        var result = await _validator.ValidateAsync(
            DeviceTestFactory.CreateRegistrationRequest(),
            TestContext.Current.CancellationToken);

        Assert.True(result.IsValid);
    }

    [Fact]
    public async Task RegistrationRequiresStableIdentityFields()
    {
        var request = new RegistrationRequest(
            string.Empty,
            new DeviceIdentityContract(string.Empty, string.Empty, "Model", "Android", "16", "1"),
            null);

        var result = await _validator.ValidateAsync(request, TestContext.Current.CancellationToken);

        Assert.False(result.IsValid);
        Assert.Contains(result.Errors, failure => failure.PropertyName == nameof(request.DisplayName));
        Assert.Contains(result.Errors, failure => failure.PropertyName.EndsWith("InstallationId", StringComparison.Ordinal));
        Assert.Contains(result.Errors, failure => failure.PropertyName.EndsWith("Manufacturer", StringComparison.Ordinal));
    }

    [Fact]
    public async Task RegistrationRejectsDuplicateCapabilitiesIgnoringCase()
    {
        var valid = DeviceTestFactory.CreateRegistrationRequest();
        var request = valid with
        {
            Capabilities =
            [
                new DeviceCapabilityContract("Camera", "1"),
                new DeviceCapabilityContract("camera", "2"),
            ],
        };

        var result = await _validator.ValidateAsync(request, TestContext.Current.CancellationToken);

        Assert.False(result.IsValid);
        Assert.Contains(result.Errors, failure => failure.ErrorMessage.Contains("unique", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task RegistrationRejectsUnsupportedSchemaVersion()
    {
        var request = DeviceTestFactory.CreateRegistrationRequest() with
        {
            ClientRegistrationSchemaVersion = RegistrationSchema.CurrentVersion + 1,
        };

        var result = await _validator.ValidateAsync(request, TestContext.Current.CancellationToken);

        Assert.False(result.IsValid);
        Assert.Contains(result.Errors, failure =>
            failure.PropertyName == nameof(RegistrationRequest.ClientRegistrationSchemaVersion));
    }
}
