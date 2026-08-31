using SentriCam.Domain.Hierarchy;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Domain;

public sealed class HierarchyFoundationTests
{
    [Fact]
    public void RegisteredDeviceHasNoHierarchyByDefault()
    {
        var device = DeviceTestFactory.CreateDevice();

        Assert.Null(device.DeviceGroupId);
    }

    [Fact]
    public void DeviceCanBeAssignedAndRemovedWithoutChangingIdentity()
    {
        var organization = Organization.Create("SentriCam", DeviceTestFactory.Now);
        var site = Site.Create(organization.Id, "Cairo", DeviceTestFactory.Now);
        var area = Area.Create(site.Id, "North Entrance", DeviceTestFactory.Now);
        var group = DeviceGroup.Create(area.Id, "Entry Cameras", DeviceTestFactory.Now);
        var device = DeviceTestFactory.CreateDevice();
        var installationId = device.Identity.InstallationId;

        device.AssignToGroup(group.Id, DeviceTestFactory.Now.AddMinutes(1));
        Assert.Equal(group.Id, device.DeviceGroupId);

        device.RemoveFromGroup(DeviceTestFactory.Now.AddMinutes(2));
        Assert.Null(device.DeviceGroupId);
        Assert.Equal(installationId, device.Identity.InstallationId);
    }
}
