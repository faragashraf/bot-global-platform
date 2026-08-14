using Microsoft.AspNetCore.Http;
using BotGlobal.Communication;
using BotGlobal.Identity;
using BotGlobal.Catalog;
using BotGlobal.Catalog.Endpoints;
using BotGlobal.PlatformClients;

var builder = WebApplication.CreateBuilder(args);

const string FrontendCorsPolicy = "Frontend";

// Add services to the container.

builder.Services.AddControllers();
builder.Services.AddProblemDetails();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();
builder.Services.AddCatalogModule(builder.Configuration);
builder.Services.AddIdentityModule(builder.Configuration);

builder.Services.AddCommunicationModule(builder.Configuration);
builder.Services.AddPlatformClientsModule(builder.Configuration);

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

app.MapControllers();
app.MapCatalogEndpoints();
app.MapAdminCatalogEndpoints();
app.MapIdentityModuleEndpoints();

await app.InitializeIdentityAsync();

app.MapCommunicationModule();
app.MapPlatformClientsModule();

app.Run();

public partial class Program;
