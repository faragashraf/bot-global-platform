using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SentriCam.Api.Controllers;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Tests.Api;

public sealed class HubTimeEndpointContractTests
{
    [Fact]
    public void EndpointIsVersionedDeviceAuthenticatedNoStoreAndThin()
    {
        var controller = typeof(HubTimeController);
        var authorize = Assert.Single(controller.GetCustomAttributes(typeof(AuthorizeAttribute), true))
            as AuthorizeAttribute;
        var route = Assert.Single(controller.GetCustomAttributes(typeof(RouteAttribute), true))
            as RouteAttribute;
        var action = controller.GetMethod(nameof(HubTimeController.Get));
        var cache = Assert.Single(action!.GetCustomAttributes(typeof(ResponseCacheAttribute), true))
            as ResponseCacheAttribute;

        Assert.Equal(AuthorizationPolicies.Device, authorize!.Policy);
        Assert.Equal("api/v{version:apiVersion}/device/time", route!.Template);
        Assert.True(cache!.NoStore);
        Assert.Equal(ResponseCacheLocation.None, cache.Location);
        Assert.Empty(action.GetParameters());
    }
}
