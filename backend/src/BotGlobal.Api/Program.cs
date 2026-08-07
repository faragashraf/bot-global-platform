using BotGlobal.Catalog;
using BotGlobal.Catalog.Endpoints;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.

builder.Services.AddControllers();
builder.Services.AddProblemDetails();
builder.Services.AddCatalogModule(builder.Configuration);

var app = builder.Build();

// Configure the HTTP request pipeline.

app.UseExceptionHandler();
app.UseHttpsRedirection();

app.UseAuthorization();

app.MapControllers();
app.MapCatalogEndpoints();

app.Run();

public partial class Program;
