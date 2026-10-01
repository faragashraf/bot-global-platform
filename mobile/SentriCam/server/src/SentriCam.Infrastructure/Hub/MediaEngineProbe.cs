using System.Diagnostics;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;

namespace SentriCam.Infrastructure.Hub;

public sealed class MediaEngineProbe : IMediaEngineProbe
{
    public HubCapabilityStatus Detect(HubMediaSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        var executable = ResolveExecutable(settings);
        if (executable is null)
        {
            return new HubCapabilityStatus(
                "missing",
                "Not available yet",
                "The Hub package will supply the media engine; setup can continue.");
        }

        try
        {
            using var process = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = executable,
                    RedirectStandardError = true,
                    RedirectStandardOutput = true,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                },
            };
            process.StartInfo.ArgumentList.Add("-version");
            if (!process.Start() || !process.WaitForExit(3_000) || process.ExitCode != 0)
            {
                TryKill(process);
                return new HubCapabilityStatus(
                    "broken",
                    "Needs attention",
                    "The media engine was found but did not start correctly.");
            }
            return new HubCapabilityStatus(
                "ready",
                "Ready",
                settings.PreferBundledEngine ? "Hub-managed media engine" : "Custom media engine");
        }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return new HubCapabilityStatus(
                "broken",
                "Needs attention",
                "The media engine was found but could not be started.");
        }
    }

    public string? ResolveExecutable(HubMediaSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        if (!settings.PreferBundledEngine && !string.IsNullOrWhiteSpace(settings.CustomPath))
        {
            return File.Exists(settings.CustomPath) ? Path.GetFullPath(settings.CustomPath) : settings.CustomPath;
        }

        foreach (var candidate in BundledCandidates())
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        var executable = OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg";
        var path = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
        foreach (var folder in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            var candidate = Path.Combine(folder, executable);
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }
        return null;
    }

    private static IEnumerable<string> BundledCandidates()
    {
        var executable = OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg";
        var platform = OperatingSystem.IsWindows()
            ? "windows"
            : OperatingSystem.IsMacOS() ? "macos" : "linux";
        yield return Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg", platform, executable);
        yield return Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg", executable);
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (InvalidOperationException)
        {
        }
    }
}
