using System.Reflection;
using SentriCam.Application.Registration;
using SentriCam.Contracts.Registration;
using SentriCam.Domain.Devices;
using SentriCam.Infrastructure.Persistence;
using SentriCam.SignalR.Hubs;

namespace SentriCam.Tests.Architecture;

public sealed class DependencyDirectionTests
{
    [Fact]
    public void DomainHasNoSentriCamProjectDependencies()
    {
        var references = SentriCamReferences(typeof(Device).Assembly);

        Assert.Empty(references);
    }

    [Fact]
    public void ApplicationDoesNotReferenceOuterLayers()
    {
        var references = SentriCamReferences(typeof(RegistrationService).Assembly);

        Assert.DoesNotContain("SentriCam.Infrastructure", references);
        Assert.DoesNotContain("SentriCam.SignalR", references);
        Assert.DoesNotContain("SentriCam.Api", references);
        Assert.DoesNotContain(references, reference => reference.Contains("Android", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void InfrastructureDoesNotReferenceTransportLayers()
    {
        var references = SentriCamReferences(typeof(SentriCamDbContext).Assembly);

        Assert.DoesNotContain("SentriCam.SignalR", references);
        Assert.DoesNotContain("SentriCam.Api", references);
    }

    [Fact]
    public void SignalRDependsOnApplicationButNotInfrastructure()
    {
        var references = SentriCamReferences(typeof(DeviceHub).Assembly);

        Assert.Contains("SentriCam.Application", references);
        Assert.DoesNotContain("SentriCam.Infrastructure", references);
        Assert.DoesNotContain("SentriCam.Api", references);
    }

    [Fact]
    public void ContractsDoNotReferenceDomainOrApplication()
    {
        var references = SentriCamReferences(typeof(RegistrationRequest).Assembly);

        Assert.DoesNotContain("SentriCam.Domain", references);
        Assert.DoesNotContain("SentriCam.Application", references);
    }

    private static HashSet<string> SentriCamReferences(Assembly assembly) =>
        assembly.GetReferencedAssemblies()
            .Select(reference => reference.Name)
            .Where(name => name is not null && name.StartsWith("SentriCam.", StringComparison.Ordinal))
            .Select(name => name!)
            .ToHashSet(StringComparer.Ordinal);
}
