using System.Security.Cryptography;
using System.Text;

namespace BotGlobal.PlatformClients.Application.Security;

public sealed record GeneratedPlatformClientSecret(
    string PlainTextSecret,
    byte[] SecretHash);

public interface IPlatformClientSecretService
{
    GeneratedPlatformClientSecret Generate();
    byte[] Hash(string plainTextSecret);
    bool Verify(string plainTextSecret, ReadOnlySpan<byte> expectedHash);
}

public sealed class PlatformClientSecretService
    : IPlatformClientSecretService
{
    public const int SecretEntropyBytes = 32;
    public const int HashBytes = 32;

    public GeneratedPlatformClientSecret Generate()
    {
        var random = RandomNumberGenerator.GetBytes(SecretEntropyBytes);

        try
        {
            var secret = Base64UrlEncode(random);
            return new GeneratedPlatformClientSecret(
                secret,
                Hash(secret));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(random);
        }
    }

    public byte[] Hash(string plainTextSecret)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(plainTextSecret);
        return SHA256.HashData(Encoding.UTF8.GetBytes(plainTextSecret));
    }

    public bool Verify(
        string plainTextSecret,
        ReadOnlySpan<byte> expectedHash)
    {
        if (string.IsNullOrWhiteSpace(plainTextSecret)
            || expectedHash.Length != HashBytes)
        {
            return false;
        }

        var actualHash = Hash(plainTextSecret);

        try
        {
            return CryptographicOperations.FixedTimeEquals(
                actualHash,
                expectedHash);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(actualHash);
        }
    }

    private static string Base64UrlEncode(ReadOnlySpan<byte> value)
        => Convert.ToBase64String(value)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
}
