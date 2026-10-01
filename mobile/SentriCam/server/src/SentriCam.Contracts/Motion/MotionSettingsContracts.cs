namespace SentriCam.Contracts.Motion;

public static class MotionSettingIds
{
    public const string Enabled = "motion.enabled";
    public const string Sensitivity = "motion.sensitivity";
    public const string AdvancedSensitivity = "motion.advancedSensitivity";
    public const string TriggerDelay = "motion.triggerDelayMillis";
    public const string StopDelay = "motion.stopDelayMillis";
    public const string Cooldown = "motion.cooldownMillis";
    public const string FrameInterval = "motion.frameIntervalMillis";
    public const string WarmupFrames = "motion.warmupFrameCount";

    public static readonly IReadOnlySet<string> UserConfigurable = new HashSet<string>(
        [Enabled, Sensitivity, AdvancedSensitivity, TriggerDelay, StopDelay, Cooldown],
        StringComparer.Ordinal);

    public static bool IsMotionSetting(string control) =>
        !string.IsNullOrWhiteSpace(control) && control.StartsWith("motion.", StringComparison.Ordinal);
}

public sealed record MotionSettingValue(
    bool? Boolean = null,
    double? Number = null,
    string? Text = null);

public sealed record MotionSettingDescriptor(
    string Id,
    bool Supported,
    bool Writable,
    MotionSettingValue CurrentValue,
    double? Minimum = null,
    double? Maximum = null,
    double? Step = null,
    IReadOnlyList<string>? AllowedValues = null,
    string? Unit = null,
    bool RequiresCameraRestart = false,
    string? Reason = null);

public sealed record MotionSettingsValues(
    bool Enabled,
    string Sensitivity,
    int AdvancedSensitivity,
    long TriggerDelayMillis,
    long StopDelayMillis,
    long CooldownMillis);

public sealed record MotionEffectiveConfiguration(
    string SelectedMode,
    string Source,
    double Threshold,
    int RequiredPositiveFrames,
    double NoiseTolerance,
    double ChangedAreaThreshold,
    double BrightnessChangeTolerance,
    string ConfirmationBehavior);

public sealed record MotionSettingsDeviceReport(
    MotionSettingsValues Settings,
    IReadOnlyList<MotionSettingDescriptor> Capabilities,
    long Version,
    MotionEffectiveConfiguration? EffectiveConfiguration = null);
