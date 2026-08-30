using BotGlobal.Communication;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Endpoints;
using BotGlobal.Pairing.Application.AdminDevicePairings;
using BotGlobal.Pairing.Endpoints;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationSecurityContractTests
{
    [Fact]
    public void Public_and_admin_dtos_expose_no_provider_credentials_or_device_tokens()
    {
        Type[] dtoTypes =
        [
            typeof(CreateNotificationCampaignRequest),
            typeof(NotificationAudiencePreviewResponse),
            typeof(NotificationCampaignAcceptedResponse),
            typeof(NotificationCampaignSummaryResponse),
            typeof(NotificationCampaignPageResponse),
            typeof(AdminDevicePairingListItem),
            typeof(AdminDevicePairingTimelineEntry),
            typeof(AdminDevicePairingDetail),
            typeof(AdminDevicePushRegistrationItem),
            typeof(AdminRevokeDeviceRequest),
            typeof(AdminRevokeDeviceResult)
        ];

        string[] forbiddenPropertyNames =
        [
            "Credential",
            "CredentialPath",
            "ConfigurationReference",
            "FirebaseProjectId",
            "PrivateKey",
            "RegistrationToken",
            "Secret"
        ];

        foreach (var dtoType in dtoTypes)
        {
            var propertyNames = dtoType
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

            Assert.DoesNotContain(
                propertyNames,
                propertyName => forbiddenPropertyNames.Any(
                    forbidden => propertyName.Contains(
                        forbidden,
                        StringComparison.OrdinalIgnoreCase)));
        }
    }

    [Fact]
    public void Provider_and_firebase_configuration_types_are_server_internal()
    {
        var exportedNames = typeof(CommunicationModule)
            .Assembly
            .GetExportedTypes()
            .Select(type => type.Name)
            .ToArray();

        Assert.DoesNotContain(
            "ApplicationPushProviderOptions",
            exportedNames);
        Assert.DoesNotContain(
            "ApplicationPushProviderConfiguration",
            exportedNames);
        Assert.DoesNotContain("FcmOptions", exportedNames);
    }
}
