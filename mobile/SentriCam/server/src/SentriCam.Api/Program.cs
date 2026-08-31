using Asp.Versioning;
using Microsoft.OpenApi.Models;
using Microsoft.Extensions.Options;
using SentriCam.Api.Authentication;
using SentriCam.Api.Errors;
using SentriCam.Api.Security;
using SentriCam.Api.Validation;
using SentriCam.Application;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Infrastructure;
using SentriCam.BuildingBlocks.Platform;
using SentriCam.Contracts.Monitoring;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Infrastructure.Authentication;
using SentriCam.SignalR;
using SentriCam.Api.Monitoring;
using SentriCam.Api.Recordings;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using SentriCam.Infrastructure.Storage;
using SentriCam.Infrastructure.Hub;
using SentriCam.Application.LiveView;
using SentriCam.Api.LiveView;
using SentriCam.Api.CameraControl;
using System.Text.Json;

const string printAdvertisedHubUrlCommand = "--print-advertised-hub-url";
var printAdvertisedHubUrl = args is [printAdvertisedHubUrlCommand];
var builder = WebApplication.CreateBuilder(printAdvertisedHubUrl ? [] : args);
if (printAdvertisedHubUrl)
{
    var resolver = new AdvertisedHubUrlResolver(
        Options.Create(new AdvertisedHubUrlOptions
        {
            AdvertisedUrl = builder.Configuration[$"{AdvertisedHubUrlOptions.SectionName}:AdvertisedUrl"],
        }),
        new SystemLocalHubIdentitySource());
    Console.WriteLine(resolver.Resolve().GetComponents(
        UriComponents.SchemeAndServer,
        UriFormat.UriEscaped));
    return;
}

var hubSetupStore = FileHubSetupStore.Bootstrap(
    builder.Configuration,
    builder.Environment.ContentRootPath,
    builder.Environment.IsDevelopment());
if (string.IsNullOrWhiteSpace(builder.Configuration["urls"]))
{
    var hubNetwork = hubSetupStore.Current.Draft.Network;
    if (hubNetwork.HttpsEnabled && hubSetupStore.Current.IsConfigured)
    {
        var certificatePath = hubNetwork.GenerateCertificate
            ? Path.Combine(hubSetupStore.RootPath, "certificates", "sentricam-hub.pfx")
            : hubNetwork.CertificatePath;
        if (string.IsNullOrWhiteSpace(certificatePath) || !File.Exists(certificatePath))
        {
            throw new InvalidOperationException("The configured Hub certificate could not be found.");
        }
        builder.WebHost.ConfigureKestrel(options =>
            options.ListenAnyIP(
                hubNetwork.Port,
                listen => listen.UseHttps(certificatePath, hubSetupStore.Current.CertificatePassword)));
    }
    else
    {
        builder.WebHost.UseUrls($"http://0.0.0.0:{hubNetwork.Port}");
    }
}
var maximumRecordingUploadBytes = builder.Configuration.GetValue<long?>(
    $"{RecordingStorageOptions.SectionName}:MaximumUploadBytes")
    ?? RecordingStorageOptions.DefaultMaximumUploadBytes;
var maximumRecordingRequestBytes = checked(
    maximumRecordingUploadBytes + RecordingStorageOptions.MultipartEnvelopeAllowanceBytes);
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = maximumRecordingRequestBytes);
builder.Services.Configure<FormOptions>(options =>
    options.MultipartBodyLengthLimit = maximumRecordingRequestBytes);

builder.Services.AddProblemDetails(options =>
    options.CustomizeProblemDetails = context =>
        context.ProblemDetails.Extensions["traceId"] = context.HttpContext.TraceIdentifier);
builder.Services.AddExceptionHandler<ApiExceptionHandler>();
builder.Services.AddScoped<ApplicationValidationFilter>();
builder.Services.AddControllers(options => options.Filters.Add<ApplicationValidationFilter>());
builder.Services
    .AddApiVersioning(options =>
    {
        options.DefaultApiVersion = new ApiVersion(1, 0);
        options.AssumeDefaultVersionWhenUnspecified = false;
        options.ReportApiVersions = true;
    })
    .AddMvc()
    .AddApiExplorer(options =>
    {
        options.GroupNameFormat = "'v'VVV";
        options.SubstituteApiVersionInUrl = true;
    });
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc(
        PlatformMetadata.ApiVersion,
        new OpenApiInfo
        {
            Title = PlatformMetadata.ApiName,
            Version = PlatformMetadata.ApiVersion,
            Description = "SentriCam device platform API.",
        });
    options.AddSecurityDefinition(
        "Bearer",
        new OpenApiSecurityScheme
        {
            Name = "Authorization",
            In = ParameterLocation.Header,
            Type = SecuritySchemeType.Http,
            Scheme = "bearer",
            BearerFormat = "JWT",
            Description = "SentriCam device or operator JWT.",
        });
    options.AddSecurityRequirement(new OpenApiSecurityRequirement
    {
        [new OpenApiSecurityScheme
        {
            Reference = new OpenApiReference
            {
                Type = ReferenceType.SecurityScheme,
                Id = "Bearer",
            },
        }] = [],
    });
});
builder.Services.AddHealthChecks();
builder.Services.AddSentriCamRateLimiting();
builder.Services.AddSentriCamApplication();
builder.Services.AddSentriCamInfrastructure(builder.Configuration, hubSetupStore);
builder.Services.AddSentriCamJwtAuthentication(builder.Configuration);
builder.Services.AddSentriCamSignalR();
if (builder.Environment.IsDevelopment())
{
    builder.Services.Configure<Microsoft.AspNetCore.SignalR.HubOptions>(options =>
        options.EnableDetailedErrors = true);
}
builder.Services.AddHostedService<DeviceCommandTimeoutWorker>();
builder.Services.AddHostedService<RecordingThumbnailWorker>();
builder.Services.AddHostedService<LiveSessionTimeoutWorker>();
builder.Services.AddHostedService<CameraControlDispatchWorker>();
builder.Services.AddHostedService<DevicePresenceSweepWorker>();

var app = builder.Build();
var logWebMonitoringTrace = LoggerMessage.Define<string>(
    LogLevel.Information,
    new EventId(500, "WebMonitoringTrace"),
    "event=web_monitoring_trace payload={Payload}");

app.UseExceptionHandler();
if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI(options =>
    {
        options.SwaggerEndpoint("/swagger/v1/swagger.json", "SentriCam Server API v1");
        options.RoutePrefix = "swagger";
    });
}
else
{
    app.UseHsts();
}

if (hubSetupStore.Current.Draft.Network.HttpsEnabled)
{
    app.UseHttpsRedirection();
}
app.UseDefaultFiles();
app.UseStaticFiles();
app.UseMiddleware<SensitiveRequestLoggingMiddleware>();
app.UseAuthentication();
app.UseAuthorization();
app.UseRateLimiter();
app.MapHealthChecks("/health");
app.MapControllers();
app.MapSentriCamDeviceHub();
app.MapSentriCamMonitoringHub();
if (app.Environment.WebRootFileProvider.GetFileInfo("index.html").Exists)
{
    app.MapFallbackToFile("index.html");
}

if (app.Environment.IsDevelopment())
{
    app.MapPost(
        "/api/v1/development/monitoring-trace",
        (JsonElement payload) =>
        {
            logWebMonitoringTrace(app.Logger, payload.GetRawText(), null);
            return Results.NoContent();
        })
    .WithName("RecordDevelopmentMonitoringTrace")
    .ExcludeFromDescription();

    app.MapPost(
        "/api/v1/development/operator-token",
        (
            IOperatorAccessTokenIssuer tokenIssuer,
            IOptions<JwtOptions> jwtOptions,
            TimeProvider timeProvider) =>
        {
            var issuedAtUtc = timeProvider.GetUtcNow();
            var token = tokenIssuer.Issue(issuedAtUtc);
            var response = new OperatorTokenResponse(
                token.Value,
                token.ExpiresAtUtc,
                "Bearer",
                jwtOptions.Value.DevelopmentOperatorDisplayName);
            return Results.Ok(response);
        })
    .WithName("CreateLocalOperatorToken")
    .WithSummary("Create a development operator session token (Development only)")
    .WithDescription(
        "Development-only endpoint. Not mapped in Production environment. "
        + "Issues a short-lived operator JWT containing the DeviceOperator role.")
    .WithTags("Development")
    .WithDisplayName("Create a local operator token (Development only)");
}

app.Run();

public partial class Program;
