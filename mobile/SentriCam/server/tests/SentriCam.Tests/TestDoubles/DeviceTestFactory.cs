using SentriCam.Contracts.Registration;
using SentriCam.Domain.Devices;

namespace SentriCam.Tests.TestDoubles;

internal static class DeviceTestFactory
{
    public static readonly DateTimeOffset Now = new(2026, 7, 31, 12, 0, 0, TimeSpan.Zero);

    public static DeviceIdentity CreateIdentity(string installationId = "installation-001") =>
        DeviceIdentity.Create(
            installationId,
            "Google",
            "Pixel 9",
            "Android",
            "16",
            "1.0.0");

    public static Device CreateDevice(string installationId = "installation-001") =>
        Device.Register(
            "Front Door",
            CreateIdentity(installationId),
            [new DeviceCapabilityDefinition("Camera", "1", true)],
            Now);

    public static RegistrationRequest CreateRegistrationRequest(
        string installationId = "installation-001") =>
        new(
            "Front Door",
            new DeviceIdentityContract(
                installationId,
                "Google",
                "Pixel 9",
                "Android",
                "16",
                "1.0.0",
                "42",
                36),
            [new DeviceCapabilityContract("Camera", "1")],
            RegistrationSchema.CurrentVersion,
            Now);
}
