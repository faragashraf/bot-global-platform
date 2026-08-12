using BotGlobal.Communication;
using BotGlobal.Identity;
using BotGlobal.Catalog;
using BotGlobal.Catalog.Endpoints;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.

builder.Services.AddControllers();
builder.Services.AddProblemDetails();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();
builder.Services.AddCatalogModule(builder.Configuration);
builder.Services.AddIdentityModule(builder.Configuration);

builder.Services.AddCommunicationModule(builder.Configuration);

var app = builder.Build();

// Configure the HTTP request pipeline.

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseExceptionHandler();
app.UseHttpsRedirection();

app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();
app.MapCatalogEndpoints();
app.MapAdminCatalogEndpoints();
app.MapIdentityModuleEndpoints();

await app.InitializeIdentityAsync();

app.MapCommunicationModule();

app.Run();

public partial class Program;
