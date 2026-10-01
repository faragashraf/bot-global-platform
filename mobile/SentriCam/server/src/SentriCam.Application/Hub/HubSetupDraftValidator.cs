using FluentValidation;
using SentriCam.Contracts.Hub;

namespace SentriCam.Application.Hub;

public sealed class HubSetupDraftValidator : AbstractValidator<HubSetupDraft>
{
    public HubSetupDraftValidator()
    {
        RuleFor(draft => draft.Mode)
            .Must(mode => HubModes.All.Contains(mode))
            .WithMessage("Choose Home, Office, or Enterprise.");
        RuleFor(draft => draft.HubName)
            .NotEmpty()
            .MaximumLength(80)
            .Matches("^[^<>:\"/\\|?*]+$")
            .WithMessage("Use a friendly Hub name without file-system punctuation.");
        RuleFor(draft => draft.RecordingFolder)
            .NotEmpty()
            .MaximumLength(1_024)
            .Must(IsSafeRecordingFolder)
            .WithMessage("Choose a full recording folder path, not the root of a drive.");
        RuleFor(draft => draft.StoragePolicy)
            .Must(policy => HubStoragePolicies.All.Contains(policy))
            .WithMessage("Choose a supported storage policy.");
        RuleFor(draft => draft.RetentionDays).InclusiveBetween(1, 3_650);
        RuleFor(draft => draft.MinimumFreeSpaceGb).InclusiveBetween(1, 10_000);
        RuleFor(draft => draft.Database.Provider)
            .Must(provider => HubDatabaseProviders.All.Contains(provider))
            .WithMessage("Choose a supported database provider.");
        RuleFor(draft => draft.Database.Provider)
            .Equal(HubDatabaseProviders.Sqlite, StringComparer.OrdinalIgnoreCase)
            .When(draft => draft.Mode.Equals(HubModes.Home, StringComparison.OrdinalIgnoreCase))
            .WithMessage("Home setup uses the private built-in data store.");
        RuleFor(draft => draft.Database.Host)
            .NotEmpty()
            .When(draft => !draft.Database.Provider.Equals(
                HubDatabaseProviders.Sqlite,
                StringComparison.OrdinalIgnoreCase));
        RuleFor(draft => draft.Database.DatabaseName)
            .NotEmpty()
            .When(draft => !draft.Database.Provider.Equals(
                HubDatabaseProviders.Sqlite,
                StringComparison.OrdinalIgnoreCase));
        RuleFor(draft => draft.Network.Port).InclusiveBetween(1_024, 65_535);
        RuleFor(draft => draft.Network.CertificatePath)
            .NotEmpty()
            .When(draft => draft.Network.HttpsEnabled && !draft.Network.GenerateCertificate);
        RuleFor(draft => draft.Media.CustomPath)
            .NotEmpty()
            .When(draft => !draft.Media.PreferBundledEngine);
    }

    private static bool IsSafeRecordingFolder(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.Contains('\0') || !Path.IsPathFullyQualified(path))
        {
            return false;
        }
        try
        {
            var fullPath = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path));
            var root = Path.TrimEndingDirectorySeparator(Path.GetPathRoot(fullPath) ?? string.Empty);
            return !string.IsNullOrWhiteSpace(root)
                && !fullPath.Equals(
                    root,
                    OperatingSystem.IsWindows()
                        ? StringComparison.OrdinalIgnoreCase
                        : StringComparison.Ordinal);
        }
        catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return false;
        }
    }
}
