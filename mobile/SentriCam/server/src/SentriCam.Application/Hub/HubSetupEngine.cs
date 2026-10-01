using FluentValidation;
using SentriCam.Contracts.Hub;

namespace SentriCam.Application.Hub;

public interface IHubSetupEngine
{
    HubSetupState GetState();

    Task<HubSetupState> SaveDraftAsync(HubSetupDraft draft, CancellationToken cancellationToken = default);

    Task<HubConnectionTestResult> TestDatabaseAsync(
        HubConnectionTestRequest request,
        CancellationToken cancellationToken = default);

    Task<HubConfigurationResult> ConfigureAsync(
        HubSetupDraft draft,
        CancellationToken cancellationToken = default);
}

public sealed class HubSetupEngine(
    IHubSetupStore store,
    IHubProvisioner provisioner,
    IValidator<HubSetupDraft> validator) : IHubSetupEngine
{
    public HubSetupState GetState()
    {
        var configuration = store.Current;
        return new HubSetupState(
            configuration.IsConfigured,
            configuration.State,
            WithoutSecrets(configuration.Draft, configuration.IsConfigured),
            configuration.IsConfigured
                ? string.Empty
                : DefaultRecordingFolder(configuration.Draft.RecordingFolder),
            [
                HubDatabaseProviders.Sqlite,
                HubDatabaseProviders.SqlServer,
                HubDatabaseProviders.PostgreSql,
            ],
            "0.3.0",
            configuration.ConfiguredAtUtc,
            configuration.Failure);
    }

    public async Task<HubSetupState> SaveDraftAsync(
        HubSetupDraft draft,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(draft);
        await validator.ValidateAndThrowAsync(draft, cancellationToken);
        await store.SaveDraftAsync(draft, cancellationToken);
        return GetState();
    }

    public Task<HubConnectionTestResult> TestDatabaseAsync(
        HubConnectionTestRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (store.Current.Draft.Mode.Equals(HubModes.Home, StringComparison.OrdinalIgnoreCase)
            && !request.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
        {
            throw new ValidationException(
                nameof(request.Database),
                [new FluentValidation.Results.ValidationFailure(
                    nameof(request.Database.Provider),
                    "Home setup uses the private built-in data store.")]);
        }
        return provisioner.TestDatabaseAsync(request.Database, cancellationToken);
    }

    public async Task<HubConfigurationResult> ConfigureAsync(
        HubSetupDraft draft,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(draft);
        try
        {
            await validator.ValidateAndThrowAsync(draft, cancellationToken);
            await store.SaveDraftAsync(draft with { CurrentStep = "configure" }, cancellationToken);
            var provisioningDraft = store.Current.Draft;

            var connectionTest = await provisioner.TestDatabaseAsync(
                provisioningDraft.Database,
                cancellationToken);
            if (!connectionTest.Success)
            {
                throw new ValidationException(
                    nameof(draft.Database),
                    [new FluentValidation.Results.ValidationFailure(
                        nameof(draft.Database),
                        connectionTest.Message)]);
            }

            var actions = await provisioner.ProvisionAsync(provisioningDraft, cancellationToken);
            await store.CompleteAsync(
                provisioningDraft with { CurrentStep = "ready" },
                provisioner.BuildConnectionString(provisioningDraft),
                cancellationToken);

            return new HubConfigurationResult(
                true,
                provisioningDraft.HubName.Trim(),
                "/",
                provisioningDraft.Network.HttpsEnabled,
                actions);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (ValidationException)
        {
            await store.MarkFailedAsync(
                draft,
                "validation_failed",
                "Some setup choices need attention. Review them and try again.",
                cancellationToken);
            throw;
        }
        catch
        {
            await store.MarkFailedAsync(
                draft,
                "provisioning_failed",
                "SentriCam could not finish preparing this Hub. Your choices are saved and setup can be retried.",
                cancellationToken);
            throw;
        }
    }

    private static HubSetupDraft WithoutSecrets(HubSetupDraft draft, bool redactPaths) => draft with
    {
        RecordingFolder = redactPaths ? FolderLabel(draft.RecordingFolder) : draft.RecordingFolder,
        Database = draft.Database with
        {
            Host = redactPaths ? null : draft.Database.Host,
            Port = redactPaths ? null : draft.Database.Port,
            DatabaseName = redactPaths
                || draft.Database.Provider.Equals(
                    HubDatabaseProviders.Sqlite,
                    StringComparison.OrdinalIgnoreCase)
                        ? null
                        : draft.Database.DatabaseName,
            Username = redactPaths ? null : draft.Database.Username,
            Password = null,
            HasStoredPassword = !redactPaths
                && (draft.Database.HasStoredPassword || !string.IsNullOrEmpty(draft.Database.Password)),
        },
        Network = redactPaths
            ? draft.Network with { CertificatePath = null }
            : draft.Network,
        Media = redactPaths
            ? draft.Media with { CustomPath = null }
            : draft.Media,
    };

    private static string FolderLabel(string path)
    {
        var label = Path.GetFileName(Path.TrimEndingDirectorySeparator(path));
        return string.IsNullOrWhiteSpace(label) ? "Configured folder" : label;
    }

    private static string DefaultRecordingFolder(string configured) =>
        string.IsNullOrWhiteSpace(configured)
            ? Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.MyVideos),
                "SentriCam")
            : configured;
}
