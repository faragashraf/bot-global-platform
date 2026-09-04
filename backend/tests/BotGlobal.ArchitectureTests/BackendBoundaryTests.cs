using System.Xml.Linq;

namespace BotGlobal.ArchitectureTests;

public sealed class BackendBoundaryTests
{
    private static readonly string RepositoryRoot = FindRepositoryRoot();

    private static readonly string ModulesRoot = NormalizeDirectory(
        Path.Combine(
            RepositoryRoot,
            "backend",
            "src",
            "Modules"));

    private static readonly string BuildingBlocksRoot = NormalizeDirectory(
        Path.Combine(
            RepositoryRoot,
            "backend",
            "src",
            "BuildingBlocks"));

    [Fact]
    public void Capability_modules_do_not_reference_other_capability_modules()
    {
        var violations = new List<string>();

        foreach (var project in GetCapabilityModuleProjects())
        {
            foreach (var reference in ResolveProjectReferences(project))
            {
                if (!IsInside(reference, ModulesRoot))
                {
                    continue;
                }

                if (SamePath(project, reference))
                {
                    continue;
                }

                violations.Add(
                    $"{Relative(project)} -> {Relative(reference)}");
            }
        }

        Assert.True(
            violations.Count == 0,
            "Capability modules must not directly reference other capability modules."
            + Environment.NewLine
            + string.Join(Environment.NewLine, violations));
    }

    [Fact]
    public void Capability_modules_may_reference_building_blocks()
    {
        var moduleProjects = GetCapabilityModuleProjects();

        var buildingBlockReferences = moduleProjects
            .SelectMany(ResolveProjectReferences)
            .Where(reference => IsInside(reference, BuildingBlocksRoot))
            .Select(Relative)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        Assert.Contains(
            buildingBlockReferences,
            path => path.EndsWith(
                "BotGlobal.SharedKernel.csproj",
                StringComparison.OrdinalIgnoreCase)
                || path.EndsWith(
                    "BotGlobal.Contracts.csproj",
                    StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Building_blocks_do_not_reference_capability_modules()
    {
        if (!Directory.Exists(BuildingBlocksRoot))
        {
            return;
        }

        var violations = Directory
            .EnumerateFiles(
                BuildingBlocksRoot,
                "*.csproj",
                SearchOption.AllDirectories)
            .SelectMany(project =>
                ResolveProjectReferences(project)
                    .Select(reference => new
                    {
                        Project = project,
                        Reference = reference
                    }))
            .Where(pair => IsInside(pair.Reference, ModulesRoot))
            .Select(pair =>
                $"{Relative(pair.Project)} -> {Relative(pair.Reference)}")
            .ToArray();

        Assert.True(
            violations.Length == 0,
            "Building blocks must not reference capability modules."
            + Environment.NewLine
            + string.Join(Environment.NewLine, violations));
    }

    [Fact]
    public void Api_can_reference_modules_for_composition()
    {
        var apiProject = Path.Combine(
            RepositoryRoot,
            "backend",
            "src",
            "BotGlobal.Api",
            "BotGlobal.Api.csproj");

        Assert.True(File.Exists(apiProject));

        var moduleReferences = ResolveProjectReferences(apiProject)
            .Where(reference => IsInside(reference, ModulesRoot))
            .ToArray();

        Assert.NotEmpty(moduleReferences);

        Assert.Contains(
            moduleReferences,
            path => path.EndsWith(
                Path.Combine(
                    "Communication",
                    "BotGlobal.Communication",
                    "BotGlobal.Communication.csproj"),
                StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Communication_does_not_reference_Realtime_or_Notifications()
    {
        var communicationProject = Path.Combine(
            RepositoryRoot,
            "backend",
            "src",
            "Modules",
            "Communication",
            "BotGlobal.Communication",
            "BotGlobal.Communication.csproj");

        Assert.True(File.Exists(communicationProject));

        var references = ResolveProjectReferences(communicationProject)
            .Select(Relative)
            .ToArray();

        Assert.DoesNotContain(
            references,
            path => path.Contains(
                "BotGlobal.Realtime",
                StringComparison.OrdinalIgnoreCase));

        Assert.DoesNotContain(
            references,
            path => path.Contains(
                "BotGlobal.Notifications",
                StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void Backend_boundaries_document_matches_realtime_ownership_decision()
    {
        var document = File.ReadAllText(
            Path.Combine(
                RepositoryRoot,
                "docs",
                "architecture",
                "03-backend-boundaries.md"));

        Assert.Contains(
            "capability-specific hubs and realtime behavior belong to the capability",
            document,
            StringComparison.OrdinalIgnoreCase);

        Assert.Contains(
            "`CommunicationHub` belongs to `BotGlobal.Communication`",
            document,
            StringComparison.Ordinal);

        Assert.DoesNotContain(
            "SignalR belongs to Realtime.",
            document,
            StringComparison.Ordinal);
    }

    [Fact]
    public void Communication_project_currently_has_no_capability_project_references()
    {
        var communicationProject = Path.Combine(
            RepositoryRoot,
            "backend",
            "src",
            "Modules",
            "Communication",
            "BotGlobal.Communication",
            "BotGlobal.Communication.csproj");

        var capabilityReferences = ResolveProjectReferences(communicationProject)
            .Where(reference => IsInside(reference, ModulesRoot))
            .Select(Relative)
            .ToArray();

        Assert.Empty(capabilityReferences);
    }

    [Fact]
    public void Mobile_profile_read_path_has_no_live_upstream_dependency()
    {
        var profileDirectory = Path.Combine(
            ModulesRoot,
            "Pairing",
            "BotGlobal.Pairing",
            "Application",
            "Profiles");
        var endpoint = Path.Combine(
            ModulesRoot,
            "Pairing",
            "BotGlobal.Pairing",
            "Endpoints",
            "MobileProfileEndpoints.cs");
        var source = Directory
            .EnumerateFiles(profileDirectory, "*.cs", SearchOption.AllDirectories)
            .Append(endpoint)
            .Select(File.ReadAllText)
            .ToArray();

        var forbidden = new[]
        {
            "HttpClient",
            "System.Net.Http",
            "ConnectV2ApiClient",
            "IConnectV2",
            "connect-v2"
        };

        foreach (var token in forbidden)
        {
            Assert.DoesNotContain(
                source,
                content => content.Contains(
                    token,
                    StringComparison.OrdinalIgnoreCase));
        }
    }

    private static IReadOnlyCollection<string> GetCapabilityModuleProjects()
    {
        return Directory
            .EnumerateFiles(
                ModulesRoot,
                "*.csproj",
                SearchOption.AllDirectories)
            .Where(path =>
                !path.Contains(
                    $"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}",
                    StringComparison.Ordinal)
                && !path.Contains(
                    $"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}",
                    StringComparison.Ordinal))
            .Select(Path.GetFullPath)
            .ToArray();
    }

    private static IReadOnlyCollection<string> ResolveProjectReferences(
        string projectPath)
    {
        var document = XDocument.Load(projectPath);
        var projectDirectory = Path.GetDirectoryName(projectPath)!;

        return document
            .Descendants("ProjectReference")
            .Select(element => element.Attribute("Include")?.Value)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .Select(reference =>
            {
                var normalizedReference = reference
                    .Replace('\\', Path.DirectorySeparatorChar)
                    .Replace('/', Path.DirectorySeparatorChar);

                return Path.GetFullPath(
                    Path.Combine(
                        projectDirectory,
                        normalizedReference));
            })
            .ToArray();
    }

    private static bool IsInside(
        string path,
        string normalizedDirectoryRoot)
    {
        var fullPath = Path.GetFullPath(path);

        return fullPath.StartsWith(
            normalizedDirectoryRoot,
            StringComparison.OrdinalIgnoreCase);
    }

    private static string NormalizeDirectory(string path)
    {
        return Path.GetFullPath(path)
            .TrimEnd(
                Path.DirectorySeparatorChar,
                Path.AltDirectorySeparatorChar)
            + Path.DirectorySeparatorChar;
    }

    private static bool SamePath(string left, string right)
    {
        return string.Equals(
            Path.GetFullPath(left),
            Path.GetFullPath(right),
            StringComparison.OrdinalIgnoreCase);
    }

    private static string Relative(string path)
    {
        return Path.GetRelativePath(
            RepositoryRoot,
            Path.GetFullPath(path));
    }

    private static string FindRepositoryRoot()
    {
        var directory = new DirectoryInfo(
            AppContext.BaseDirectory);

        while (directory is not null)
        {
            var solution = Path.Combine(
                directory.FullName,
                "backend",
                "BotGlobal.sln");

            var docs = Path.Combine(
                directory.FullName,
                "docs");

            if (File.Exists(solution)
                && Directory.Exists(docs))
            {
                return directory.FullName;
            }

            directory = directory.Parent;
        }

        throw new InvalidOperationException(
            "Bot Global repository root could not be located.");
    }
}
