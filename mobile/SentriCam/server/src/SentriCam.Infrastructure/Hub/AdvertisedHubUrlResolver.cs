using System.ComponentModel;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Microsoft.Extensions.Options;
using SentriCam.Application.Hub;

namespace SentriCam.Infrastructure.Hub;

public sealed class AdvertisedHubUrlOptions
{
    public const string SectionName = "SentriCam:Hub";

    public string? AdvertisedUrl { get; set; }
}

public interface ILocalHubIdentitySource
{
    string? GetHostName();

    IReadOnlyCollection<IPAddress> GetLanIPv4Addresses();
}

public sealed class AdvertisedHubUrlResolver(
    IOptions<AdvertisedHubUrlOptions> options,
    ILocalHubIdentitySource identitySource) : IAdvertisedHubUrlResolver
{
    private readonly AdvertisedHubUrlOptions _options = options.Value;

    public Uri Resolve()
    {
        if (!string.IsNullOrWhiteSpace(_options.AdvertisedUrl))
        {
            return NormalizeExplicit(_options.AdvertisedUrl);
        }

        var address = identitySource.GetLanIPv4Addresses()
            .Where(IsUsableLanIPv4Address)
            .Distinct()
            .FirstOrDefault();
        if (address is not null)
        {
            return BuildHttpsUri(address.ToString());
        }

        var hostName = NormalizeHost(identitySource.GetHostName());
        if (hostName is not null)
        {
            return BuildHttpsUri(hostName);
        }

        throw new InvalidOperationException(
            "SentriCam could not determine a usable hostname or LAN IPv4 address for the advertised Hub URL. "
            + "Configure SentriCam:Hub:AdvertisedUrl with the reverse proxy's HTTPS URL.");
    }

    private static Uri NormalizeExplicit(string value)
    {
        var trimmed = value.Trim();
        if (!Uri.TryCreate(trimmed, UriKind.Absolute, out var uri)
            || !uri.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)
            || string.IsNullOrWhiteSpace(uri.Host)
            || !string.IsNullOrEmpty(uri.UserInfo)
            || !string.IsNullOrEmpty(uri.Query)
            || !string.IsNullOrEmpty(uri.Fragment)
            || uri.Port == 0
            || (uri.AbsolutePath.Length > 0 && uri.AbsolutePath != "/")
            || NormalizeHost(uri.Host) is null)
        {
            throw new InvalidOperationException(
                "SentriCam:Hub:AdvertisedUrl must be an absolute HTTPS origin without credentials, a path, a query, or a fragment.");
        }

        return new UriBuilder(Uri.UriSchemeHttps, uri.IdnHost)
        {
            Port = uri.IsDefaultPort ? -1 : uri.Port,
        }.Uri;
    }

    private static Uri BuildHttpsUri(string host) => new UriBuilder(Uri.UriSchemeHttps, host)
    {
        Port = -1,
    }.Uri;

    private static string? NormalizeHost(string? value)
    {
        var host = value?.Trim().TrimEnd('.');
        if (string.IsNullOrWhiteSpace(host)
            || host.Equals("localhost", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        if (IPAddress.TryParse(host, out var address))
        {
            return IsUsableLanIPv4Address(address) ? address.ToString() : null;
        }

        return Uri.CheckHostName(host) == UriHostNameType.Dns ? host : null;
    }

    private static bool IsUsableLanIPv4Address(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork
            || IPAddress.IsLoopback(address)
            || address.Equals(IPAddress.Any)
            || address.Equals(IPAddress.None)
            || address.Equals(IPAddress.Broadcast))
        {
            return false;
        }

        var bytes = address.GetAddressBytes();
        return bytes[0] is > 0 and < 224
            && !(bytes[0] == 169 && bytes[1] == 254);
    }
}

public sealed class SystemLocalHubIdentitySource : ILocalHubIdentitySource
{
    public string? GetHostName()
    {
        if (OperatingSystem.IsMacOS())
        {
            var localHostName = ReadMacOsLocalHostName();
            if (!string.IsNullOrWhiteSpace(localHostName))
            {
                return $"{localHostName}.local";
            }
        }

        try
        {
            var hostName = Dns.GetHostName().Trim();
            if (string.IsNullOrWhiteSpace(hostName))
            {
                return null;
            }
            return OperatingSystem.IsMacOS() && !hostName.Contains('.', StringComparison.Ordinal)
                ? $"{hostName}.local"
                : hostName;
        }
        catch (SocketException)
        {
            return null;
        }
    }

    public IReadOnlyCollection<IPAddress> GetLanIPv4Addresses()
    {
        NetworkInterface[] interfaces;
        try
        {
            interfaces = NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException)
        {
            return [];
        }

        var candidates = new List<AddressCandidate>();
        foreach (var networkInterface in interfaces)
        {
            if (networkInterface.OperationalStatus != OperationalStatus.Up
                || networkInterface.NetworkInterfaceType is NetworkInterfaceType.Loopback
                    or NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            try
            {
                var priority = InterfacePriority(networkInterface.NetworkInterfaceType);
                candidates.AddRange(networkInterface.GetIPProperties().UnicastAddresses
                    .Where(item => item.Address.AddressFamily == AddressFamily.InterNetwork)
                    .Select(item => new AddressCandidate(
                        priority,
                        networkInterface.Name,
                        item.Address)));
            }
            catch (NetworkInformationException)
            {
                // An interface can disappear between enumeration and inspection.
            }
        }

        return candidates
            .OrderBy(item => item.InterfacePriority)
            .ThenBy(item => item.InterfaceName, StringComparer.Ordinal)
            .ThenBy(item => Convert.ToHexString(item.Address.GetAddressBytes()), StringComparer.Ordinal)
            .Select(item => item.Address)
            .ToArray();
    }

    private static string? ReadMacOsLocalHostName()
    {
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = "/usr/sbin/scutil",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            startInfo.ArgumentList.Add("--get");
            startInfo.ArgumentList.Add("LocalHostName");
            using var process = Process.Start(startInfo);
            if (process is null || !process.WaitForExit(2_000) || process.ExitCode != 0)
            {
                if (process is { HasExited: false })
                {
                    process.Kill(entireProcessTree: true);
                }
                return null;
            }
            return process.StandardOutput.ReadToEnd().Trim();
        }
        catch (Exception exception) when (exception is InvalidOperationException or Win32Exception)
        {
            return null;
        }
    }

    private static int InterfacePriority(NetworkInterfaceType type) => type switch
    {
        NetworkInterfaceType.Ethernet or
        NetworkInterfaceType.Ethernet3Megabit or
        NetworkInterfaceType.FastEthernetFx or
        NetworkInterfaceType.FastEthernetT or
        NetworkInterfaceType.GigabitEthernet or
        NetworkInterfaceType.Wireless80211 => 0,
        _ => 1,
    };

    private sealed record AddressCandidate(
        int InterfacePriority,
        string InterfaceName,
        IPAddress Address);
}
