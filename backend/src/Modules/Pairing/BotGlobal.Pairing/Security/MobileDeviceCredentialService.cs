using System.Security.Cryptography;

namespace BotGlobal.Pairing.Security;

public sealed record MobileDeviceCredential(
    string PlainText,
    byte[] Hash);

public interface IMobileDeviceCredentialService
{
    MobileDeviceCredential Generate();

    byte[] Hash(string credential);
}

public sealed class MobileDeviceCredentialService
    : IMobileDeviceCredentialService
{
    private const int CredentialBytes = 32;

    public MobileDeviceCredential Generate()
    {
        var bytes = RandomNumberGenerator.GetBytes(
            CredentialBytes);

        var plainText =
            Convert.ToBase64String(bytes)
                .TrimEnd('=')
                .Replace('+', '-')
                .Replace('/', '_');

        return new MobileDeviceCredential(
            plainText,
            SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes(
                    plainText)));
    }

    public byte[] Hash(string credential)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            credential);

        return SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(
                credential));
    }
}
