using BotGlobal.PlatformClients.Application.Security;
using BotGlobal.PlatformClients.Domain;

namespace BotGlobal.UnitTests.PlatformClients;

public sealed class PlatformClientDomainTests
{
    private static readonly DateTimeOffset Now =
        new(2026, 8, 14, 0, 0, 0, TimeSpan.Zero);

    [Fact]
    public void Client_key_is_normalized()
    {
        var client = PlatformClient.Create(
            " Organization-Gateway ",
            "Organization Gateway",
            Now);

        Assert.Equal("organization-gateway", client.ClientKey);
        Assert.Equal(PlatformClientStatus.Active, client.Status);
    }

    [Fact]
    public void Client_key_rejects_spaces()
    {
        Assert.Throws<ArgumentException>(
            () => PlatformClient.Create(
                "connect v2",
                "Connect V2",
                Now));
    }

    [Fact]
    public void Disabled_client_cannot_receive_new_credential()
    {
        var client = PlatformClient.Create("client-a", "Client A", Now);
        client.Disable(Now.AddMinutes(1));

        Assert.Throws<InvalidOperationException>(
            () => client.AddCredential(
                new byte[32],
                Now.AddMinutes(2),
                null));
    }

    [Fact]
    public void Capability_is_normalized_and_idempotent()
    {
        var client = PlatformClient.Create("client-a", "Client A", Now);

        client.GrantCapability(" Pairing:Create ", Now);
        client.GrantCapability("pairing:create", Now.AddSeconds(1));

        Assert.Single(client.Capabilities);
        Assert.True(client.HasCapability("PAIRING:CREATE"));
    }

    [Fact]
    public void Generated_secret_verifies_and_wrong_secret_fails()
    {
        var service = new PlatformClientSecretService();
        var generated = service.Generate();

        Assert.Equal(32, generated.SecretHash.Length);
        Assert.True(service.Verify(
            generated.PlainTextSecret,
            generated.SecretHash));
        Assert.False(service.Verify(
            generated.PlainTextSecret + "x",
            generated.SecretHash));
    }

    [Fact]
    public void Generated_secrets_are_unique()
    {
        var service = new PlatformClientSecretService();
        var first = service.Generate();
        var second = service.Generate();

        Assert.NotEqual(first.PlainTextSecret, second.PlainTextSecret);
        Assert.False(first.SecretHash.SequenceEqual(second.SecretHash));
    }

    [Fact]
    public void Credential_expiry_and_revocation_are_enforced()
    {
        var client = PlatformClient.Create("client-a", "Client A", Now);

        var credential = client.AddCredential(
            new byte[32],
            Now,
            Now.AddHours(1));

        Assert.True(credential.IsUsableAt(Now.AddMinutes(30)));
        Assert.False(credential.IsUsableAt(Now.AddHours(1)));

        credential.Revoke(Now.AddMinutes(10));

        Assert.False(credential.IsUsableAt(Now.AddMinutes(20)));
    }
}
