using BotGlobal.Catalog;
using BotGlobal.Catalog.Endpoints;
using BotGlobal.Calling;
using BotGlobal.Communication;
using BotGlobal.Communication.Endpoints;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games;
using BotGlobal.Identity;
using BotGlobal.Notifications;
using BotGlobal.Pairing;
using BotGlobal.Pairing.Endpoints;
using BotGlobal.Pairing.Security;
using BotGlobal.PlatformClients;
using BotGlobal.PlatformClients.Authentication;
using BotGlobal.PlatformClients.Authorization;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;

var builder = WebApplication.CreateBuilder(args);

builder.Services
    .AddAuthentication()
    .AddScheme<
        AuthenticationSchemeOptions,
        MobileDeviceAuthenticationHandler>(
        MobileDeviceAuthenticationDefaults.Scheme,
        _ => { });


const string FrontendCorsPolicy = "Frontend";

// Add services to the container.

builder.Services.AddControllers();
builder.Services.AddProblemDetails();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();
builder.Services.AddCatalogModule(builder.Configuration);
builder.Services.AddIdentityModule(builder.Configuration);
builder.Services.AddCallingModule(builder.Configuration);

builder.Services.AddCommunicationModule(builder.Configuration);
builder.Services.AddPlatformClientsModule(builder.Configuration);
builder.Services.AddPairingModule(builder.Configuration);
builder.Services.AddNotificationsModule(builder.Configuration);
builder.Services.AddGamesModule(builder.Configuration);

var frontendOrigins =
    builder.Configuration
        .GetSection("Frontend:AllowedOrigins")
        .Get<string[]>()
    ?? [];

builder.Services.AddCors(options =>
{
    options.AddPolicy(
        FrontendCorsPolicy,
        policy =>
        {
            policy
                .WithOrigins(frontendOrigins)
                .AllowAnyHeader()
                .AllowAnyMethod()
                .AllowCredentials();
        });
});

builder.Services.ConfigureApplicationCookie(options =>
{
    options.Cookie.SameSite = SameSiteMode.None;
    options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
});

var app = builder.Build();

// Configure the HTTP request pipeline.

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseExceptionHandler();
app.UseHttpsRedirection();

app.UseCors(FrontendCorsPolicy);

app.UseAuthentication();
app.UseAuthorization();
app.UseRateLimiter();

app.MapControllers();
app.MapCatalogEndpoints();
app.MapAdminCatalogEndpoints();
app.MapIdentityModuleEndpoints();

await app.InitializeIdentityAsync();

app.MapCallingModule();

app.MapCommunicationModule(
    new MobileNotificationMachineAuthorizationOptions(
        PlatformClientAuthenticationDefaults.ClientIdClaim,
        PlatformClientPolicies.Capability));
app.MapPlatformClientsModule();
app.MapPairingModule(
    new PairingMachineAuthorizationOptions(
        PlatformClientAuthenticationDefaults.ClientIdClaim,
        PlatformClientPolicies.Capability));
app.MapNotificationsModule();
app.MapGamesModule();

app.Run();

public partial class Program;
