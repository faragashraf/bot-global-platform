using Microsoft.AspNetCore.Authorization;
using SentriCam.Api.Controllers;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Tests.Api;

public sealed class RecordingEndpointArchitectureTests
{
    [Fact]
    public void UploadEndpointRequiresDeviceCredentialPolicy()
    {
        var authorization = Assert.Single(
            typeof(RecordingUploadsController).GetCustomAttributes(typeof(AuthorizeAttribute), inherit: true)
                .Cast<AuthorizeAttribute>());
        Assert.Equal(AuthorizationPolicies.Device, authorization.Policy);
    }

    [Fact]
    public void OperatorRecordingEndpointsRequireDeviceControlPolicy()
    {
        var authorization = Assert.Single(
            typeof(RecordingsController).GetCustomAttributes(typeof(AuthorizeAttribute), inherit: true)
                .Cast<AuthorizeAttribute>());
        Assert.Equal(AuthorizationPolicies.DeviceControl, authorization.Policy);
    }
}
