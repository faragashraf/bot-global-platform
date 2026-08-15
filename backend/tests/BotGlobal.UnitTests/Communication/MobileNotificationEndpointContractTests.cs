using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Communication.Endpoints;

namespace BotGlobal.UnitTests.Communication;

public sealed class MobileNotificationEndpointContractTests
{
    [Fact]
    public void SendCapability_IsStable()
    {
        Assert.Equal(
            "notifications:send",
            MobileNotificationEndpoints.SendCapability);
    }

    [Fact]
    public void SendRequest_DoesNotAcceptPlatformClientIdentity()
    {
        var properties =
            typeof(SendMobileNotificationRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.DoesNotContain(
            "PlatformClientId",
            properties);

        Assert.DoesNotContain(
            "ClientKey",
            properties);

        Assert.DoesNotContain(
            "ClientSecret",
            properties);
    }

    [Fact]
    public void InitialContract_HasNoAttachmentPayload()
    {
        var properties =
            typeof(SendMobileNotificationRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.DoesNotContain(
            "Attachments",
            properties);

        Assert.DoesNotContain(
            "ImageUrl",
            properties);

        Assert.DoesNotContain(
            "FileUrl",
            properties);
    }
}
