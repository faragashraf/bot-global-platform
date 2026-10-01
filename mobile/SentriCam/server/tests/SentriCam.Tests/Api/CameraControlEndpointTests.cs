using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using SentriCam.Api.CameraControl;
using SentriCam.Application.CameraControl;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.CameraControl;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Common;

namespace SentriCam.Tests.Api;

public sealed class CameraControlEndpointTests
{
    [Fact]
    public void CameraControlDispatcherIsRegisteredAtApiStartup()
    {
        using var profile = new TemporaryProfile();
        using var app = CreateFactory(profile.Path, new FakeCameraControlEngine());
        using var client = app.CreateClient();

        Assert.Contains(
            app.Services.GetServices<IHostedService>(),
            service => service is CameraControlDispatchWorker);
    }

    [Fact]
    public void CommandCreationRouteUsesDeviceIdAndHasNoFakeCommandLookupTarget()
    {
        var action = typeof(SentriCam.Api.Controllers.CameraControlController)
            .GetMethod(nameof(SentriCam.Api.Controllers.CameraControlController.SubmitAsync));
        var route = Assert.Single(action!.GetCustomAttributes(typeof(HttpPostAttribute), inherit: true))
            as HttpPostAttribute;

        Assert.Equal("{deviceId:guid}/commands", route!.Template);
        Assert.Contains(action.GetParameters(), parameter => parameter.Name == "deviceId");
        Assert.DoesNotContain(
            typeof(SentriCam.Api.Controllers.CameraControlController).GetMethods(),
            method => method.GetCustomAttributes(typeof(HttpGetAttribute), inherit: true)
                .Cast<HttpGetAttribute>()
                .Any(attribute => attribute.Template?.Contains("commandId", StringComparison.Ordinal) == true));
    }

    [Fact]
    public async Task ValidCommandReturnsAcceptedWithoutFakeLocationAndRetryIsIdempotent()
    {
        using var profile = new TemporaryProfile();
        var engine = new FakeCameraControlEngine();
        await using var app = CreateFactory(profile.Path, engine);
        using var client = app.CreateClient();
        await AuthenticateAsync(client, TestContext.Current.CancellationToken);
        var deviceId = Guid.NewGuid();
        var request = new CameraControlCommandRequest(
            CameraControlIds.Zoom,
            new CameraControlValue(Number: 2),
            "stable-request-id");

        var response = await client.PostAsJsonAsync(
            $"/api/v1/camera-control/devices/{deviceId}/commands",
            request,
            TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
        Assert.Null(response.Headers.Location);
        var command = await response.Content.ReadFromJsonAsync<CameraControlCommandView>(
            cancellationToken: TestContext.Current.CancellationToken);
        Assert.NotNull(command);
        Assert.Equal(deviceId, command!.DeviceId);
        Assert.Equal("stable-request-id", command.CorrelationId);

        var status = await client.GetFromJsonAsync<CameraControlCenterView>(
            $"/api/v1/camera-control/devices/{deviceId}",
            TestContext.Current.CancellationToken);
        Assert.Equal(command.CommandId, Assert.Single(status!.RecentCommands).CommandId);

        var retry = await client.PostAsJsonAsync(
            $"/api/v1/camera-control/devices/{deviceId}/commands",
            request,
            TestContext.Current.CancellationToken);
        Assert.Equal(HttpStatusCode.Accepted, retry.StatusCode);
        Assert.Null(retry.Headers.Location);
        var retried = await retry.Content.ReadFromJsonAsync<CameraControlCommandView>(
            cancellationToken: TestContext.Current.CancellationToken);
        Assert.Equal(command.CommandId, retried!.CommandId);
        Assert.Equal(1, engine.CreatedCommandCount);
    }

    [Fact]
    public async Task UnsupportedCommandReturnsStructuredClientError()
    {
        using var profile = new TemporaryProfile();
        await using var app = CreateFactory(profile.Path, new FakeCameraControlEngine());
        using var client = app.CreateClient();
        await AuthenticateAsync(client, TestContext.Current.CancellationToken);

        var response = await client.PostAsJsonAsync(
            $"/api/v1/camera-control/devices/{Guid.NewGuid()}/commands",
            new CameraControlCommandRequest(
                "unsupported",
                new CameraControlValue(Boolean: true),
                "unsupported-request"),
            TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.UnprocessableEntity, response.StatusCode);
        var problem = await response.Content.ReadFromJsonAsync<ProblemDetails>(
            cancellationToken: TestContext.Current.CancellationToken);
        Assert.NotNull(problem);
        Assert.Equal(StatusCodes.Status422UnprocessableEntity, problem!.Status);
        Assert.Equal("Business rule violation", problem.Title);
        Assert.True(problem.Extensions.ContainsKey("traceId"));
    }

    [Fact]
    public async Task RecordingStartReturnsStructuredAcceptedCommand()
    {
        using var profile = new TemporaryProfile();
        await using var app = CreateFactory(profile.Path, new FakeCameraControlEngine());
        using var client = app.CreateClient();
        await AuthenticateAsync(client, TestContext.Current.CancellationToken);
        var deviceId = Guid.NewGuid();

        var response = await client.PostAsJsonAsync(
            $"/api/v1/camera-control/devices/{deviceId}/commands",
            new CameraControlCommandRequest(
                CameraControlIds.Recording,
                new CameraControlValue(Text: CameraControlValues.Start),
                "recording-start"),
            TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
        var command = await response.Content.ReadFromJsonAsync<CameraControlCommandView>(
            cancellationToken: TestContext.Current.CancellationToken);
        Assert.NotNull(command);
        Assert.Equal(CameraControlCommandState.Queued, command!.State);
        Assert.Equal(CameraControlIds.Recording, command.Control);
        Assert.Equal(CameraControlValues.Start, command.Value.Text);
    }

    [Fact]
    public async Task GroupActionsRequireOperatorAuthorization()
    {
        using var profile = new TemporaryProfile();
        await using var app = CreateFactory(profile.Path, new FakeCameraControlEngine());
        using var client = app.CreateClient();

        var response = await client.PostAsJsonAsync(
            "/api/v1/camera-control/group-actions",
            new CameraControlGroupCommandRequest(
                [Guid.NewGuid(), Guid.NewGuid()],
                CameraControlIds.Recording,
                new CameraControlValue(Text: CameraControlValues.Start),
                "unauthorized-group"),
            TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    private static WebApplicationFactory<Program> CreateFactory(
        string profilePath,
        ICameraControlEngine engine) =>
        new WebApplicationFactory<Program>().WithWebHostBuilder(builder =>
        {
            builder.UseEnvironment("Development");
            builder.UseSetting("SentriCam:Home", profilePath);
            builder.UseSetting("SentriCam:FreshInstall", "true");
            builder.UseSetting("ConnectionStrings:SentriCam", "Server=ambient;Database=ambient;");
            builder.UseSetting("Authentication:Jwt:Issuer", "SentriCam.Server.Tests");
            builder.UseSetting("Authentication:Jwt:Audience", "SentriCam.TestClients");
            builder.UseSetting(
                "Authentication:Jwt:SigningKey",
                "test-only-signing-key-with-at-least-thirty-two-chars");
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<ICameraControlEngine>();
                services.AddSingleton(engine);
            });
        });

    private static async Task AuthenticateAsync(HttpClient client, CancellationToken cancellationToken)
    {
        var response = await client.PostAsync("/api/v1/development/operator-token", null, cancellationToken);
        response.EnsureSuccessStatusCode();
        var token = await response.Content.ReadFromJsonAsync<OperatorTokenResponse>(
            cancellationToken: cancellationToken);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token!.AccessToken);
    }

    private sealed class FakeCameraControlEngine : ICameraControlEngine
    {
        private readonly Dictionary<(Guid DeviceId, string CorrelationId), CameraControlCommandView> _commands = [];
        public int CreatedCommandCount { get; private set; }

        public Task<CameraControlCenterView> GetAsync(
            Guid deviceId,
            CancellationToken cancellationToken = default)
        {
            var commands = _commands.Values.Where(command => command.DeviceId == deviceId).ToArray();
            return Task.FromResult(new CameraControlCenterView(
                deviceId,
                true,
                CameraControlSettings.Default,
                null,
                commands,
                DateTimeOffset.UtcNow));
        }

        public Task<CameraControlCommandView> SubmitAsync(
            Guid deviceId,
            CameraControlCommandRequest request,
            CameraControlActor actor,
            CancellationToken cancellationToken = default)
        {
            if (request.Control == "unsupported")
            {
                throw new DomainValidationException("This camera control is unsupported.");
            }
            var correlationId = request.CorrelationId ?? string.Empty;
            if (_commands.TryGetValue((deviceId, correlationId), out var existing))
            {
                return Task.FromResult(existing);
            }
            var command = new CameraControlCommandView(
                Guid.NewGuid(),
                deviceId,
                request.Control,
                request.Value,
                CameraControlCommandState.Queued,
                0,
                true,
                true,
                correlationId,
                actor.Subject,
                null,
                DateTimeOffset.UtcNow,
                null);
            _commands.Add((deviceId, correlationId), command);
            CreatedCommandCount++;
            return Task.FromResult(command);
        }

        public Task<CameraControlCommandView> CancelAsync(
            Guid deviceId,
            Guid commandId,
            CameraControlActor actor,
            CancellationToken cancellationToken = default) =>
            throw new NotImplementedException();

        public Task<int> ResolveForDeviceRemovalAsync(
            Guid deviceId,
            CameraControlActor actor,
            CancellationToken cancellationToken = default) => Task.FromResult(0);

        public Task ReportAsync(
            Guid authenticatedDeviceId,
            string connectionId,
            CameraControlDeviceReport report,
            CancellationToken cancellationToken = default) =>
            throw new NotImplementedException();

        public Task CompleteAsync(
            Guid authenticatedDeviceId,
            string connectionId,
            CameraControlCommandResult result,
            CancellationToken cancellationToken = default) =>
            throw new NotImplementedException();
    }

    private sealed class TemporaryProfile : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            $"sentricam-camera-control-api-{Guid.NewGuid():N}");

        public TemporaryProfile() => Directory.CreateDirectory(Path);

        public void Dispose()
        {
            if (Directory.Exists(Path)) Directory.Delete(Path, recursive: true);
        }
    }
}
