using FluentValidation;
using SentriCam.Contracts.Registration;

namespace SentriCam.Application.Registration;

public sealed class RegistrationRequestValidator : AbstractValidator<RegistrationRequest>
{
    public RegistrationRequestValidator()
    {
        RuleFor(request => request.DisplayName)
            .NotEmpty()
            .MaximumLength(200);

        RuleFor(request => request.Identity)
            .NotNull()
            .SetValidator(new DeviceIdentityContractValidator());

        RuleForEach(request => request.Capabilities)
            .SetValidator(new DeviceCapabilityContractValidator());

        RuleFor(request => request.Capabilities)
            .Must(capabilities => capabilities is null || capabilities.Count <= 100)
            .WithMessage("A registration can advertise at most 100 capabilities.")
            .Must(HaveUniqueNames)
            .WithMessage("Capability names must be unique.");

        RuleFor(request => request.ClientRegistrationSchemaVersion)
            .InclusiveBetween(1, RegistrationSchema.CurrentVersion)
            .WithMessage($"Registration schema version must be between 1 and {RegistrationSchema.CurrentVersion}.");
    }

    private static bool HaveUniqueNames(IReadOnlyCollection<DeviceCapabilityContract>? capabilities)
    {
        if (capabilities is null)
        {
            return true;
        }

        return capabilities
            .Select(capability => capability.Name?.Trim())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Count() == capabilities.Count;
    }
}

public sealed class DeviceIdentityContractValidator : AbstractValidator<DeviceIdentityContract>
{
    public DeviceIdentityContractValidator()
    {
        RuleFor(identity => identity.InstallationId).NotEmpty().MaximumLength(128);
        RuleFor(identity => identity.Manufacturer).NotEmpty().MaximumLength(100);
        RuleFor(identity => identity.Model).NotEmpty().MaximumLength(100);
        RuleFor(identity => identity.Platform).NotEmpty().MaximumLength(50);
        RuleFor(identity => identity.OperatingSystemVersion).NotEmpty().MaximumLength(50);
        RuleFor(identity => identity.AppVersion).NotEmpty().MaximumLength(50);
        RuleFor(identity => identity.AppBuild).MaximumLength(50);
        RuleFor(identity => identity.ApiLevel)
            .InclusiveBetween(1, 10_000)
            .When(identity => identity.ApiLevel.HasValue);
    }
}

public sealed class DeviceCapabilityContractValidator : AbstractValidator<DeviceCapabilityContract>
{
    public DeviceCapabilityContractValidator()
    {
        RuleFor(capability => capability.Name).NotEmpty().MaximumLength(100);
        RuleFor(capability => capability.Version).NotEmpty().MaximumLength(50);
    }
}
