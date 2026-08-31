using System.Text.Json;
using System.Text.Json.Serialization;
using System.Globalization;
using SentriCam.Contracts.CameraControl;
using SentriCam.Contracts.LiveView;
using SentriCam.Contracts.Motion;
using SentriCam.Contracts.SignalR;

namespace SentriCam.Tests.SignalR;

public sealed class AndroidContractCompatibilityTests
{
    private static readonly JsonSerializerOptions AndroidJson = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
    };

    [Fact]
    public void DeviceHubSurfaceUsesOnlyStableContractDtos()
    {
        var contractAssembly = typeof(IDeviceHubClient).Assembly;
        var wireTypes = typeof(IDeviceHubClient).GetMethods()
            .Concat(typeof(IDeviceHubServer).GetMethods())
            .SelectMany(method => method.GetParameters().Select(parameter => parameter.ParameterType))
            .ToArray();

        Assert.All(wireTypes, type => Assert.Equal(contractAssembly, type.Assembly));
    }

    [Fact]
    public void CameraCommandSerializesWithAndroidCompatibleCamelCaseAndStableValues()
    {
        var command = new CameraControlCommandEnvelope(
            Guid.Parse("3c90f42c-0f76-44aa-a2e2-eacbdbe0264e"),
            Guid.Parse("fa1a0ba8-59a4-4c80-97c1-ddc5771f5eed"),
            CameraControlIds.Torch,
            new CameraControlValue(Boolean: true),
            CameraControlSettings.Default with { Torch = true },
            1,
            DateTimeOffset.Parse("2026-08-02T09:00:00Z", CultureInfo.InvariantCulture));

        using var json = JsonDocument.Parse(JsonSerializer.Serialize(command, AndroidJson));
        var root = json.RootElement;

        Assert.Equal("3c90f42c-0f76-44aa-a2e2-eacbdbe0264e", root.GetProperty("commandId").GetString());
        Assert.Equal("torch", root.GetProperty("control").GetString());
        Assert.True(root.GetProperty("value").GetProperty("boolean").GetBoolean());
        Assert.Equal("back", root.GetProperty("desiredSettings").GetProperty("lens").GetString());
    }

    [Fact]
    public void MissingAndUnknownOptionalLiveFieldsRemainBackwardCompatible()
    {
        const string fixture = """
            {
              "sessionId":"19d6cf91-55c7-4f0e-b7f3-809dfcb45d31",
              "candidate":"candidate-value",
              "sdpMid":null,
              "sdpMLineIndex":null,
              "futureOptionalField":2
            }
            """;

        var candidate = JsonSerializer.Deserialize<LiveIceCandidate>(fixture, AndroidJson);

        Assert.NotNull(candidate);
        Assert.Null(candidate.SdpMid);
        Assert.Null(candidate.SdpMLineIndex);
        Assert.Null(candidate.UsernameFragment);
    }

    [Fact]
    public void CameraControlEnumRepresentationIsStableString()
    {
        Assert.Equal("\"Succeeded\"", JsonSerializer.Serialize(CameraControlCommandState.Succeeded, AndroidJson));
    }

    [Fact]
    public void MotionCommandAndReportUseAndroidCompatibleVersionedJson()
    {
        var command = new CameraControlCommandEnvelope(
            Guid.NewGuid(),
            Guid.NewGuid(),
            MotionSettingIds.Sensitivity,
            new CameraControlValue(Text: "high"),
            CameraControlSettings.Default,
            1,
            DateTimeOffset.Parse("2026-08-02T09:00:00Z", CultureInfo.InvariantCulture),
            ExpectedVersion: 7);
        var report = new MotionSettingsDeviceReport(
            new MotionSettingsValues(true, "medium", 50, 1_000, 10_000, 5_000),
            [new MotionSettingDescriptor(
                MotionSettingIds.Sensitivity,
                Supported: true,
                Writable: true,
                CurrentValue: new MotionSettingValue(Text: "medium"),
                AllowedValues: ["low", "medium", "high", "advanced"])],
            Version: 7,
            EffectiveConfiguration: new MotionEffectiveConfiguration(
                "medium", "preset", 0.075, 3, 0.014, 0.050, 0.220, "balanced"));

        using var commandJson = JsonDocument.Parse(JsonSerializer.Serialize(command, AndroidJson));
        using var reportJson = JsonDocument.Parse(JsonSerializer.Serialize(report, AndroidJson));

        Assert.Equal("motion.sensitivity", commandJson.RootElement.GetProperty("control").GetString());
        Assert.Equal(7, commandJson.RootElement.GetProperty("expectedVersion").GetInt64());
        Assert.Equal("high", commandJson.RootElement.GetProperty("value").GetProperty("text").GetString());
        Assert.Equal(7, reportJson.RootElement.GetProperty("version").GetInt64());
        Assert.Equal("medium", reportJson.RootElement.GetProperty("settings").GetProperty("sensitivity").GetString());
        Assert.Equal("motion.sensitivity", reportJson.RootElement.GetProperty("capabilities")[0].GetProperty("id").GetString());
        Assert.Equal("preset", reportJson.RootElement.GetProperty("effectiveConfiguration").GetProperty("source").GetString());
        Assert.Equal(0.075, reportJson.RootElement.GetProperty("effectiveConfiguration").GetProperty("threshold").GetDouble(), 0.0001);
    }
}
