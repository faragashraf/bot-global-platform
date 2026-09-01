using System.Security.Claims;
using BotGlobal.Calling.Realtime;
using BotGlobal.Calling.Application;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling;

public static class CallingModule
{
    public const string ConnectionStringName = "Communication";
    public const string DatabaseSchema = "calling";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";
    public static IServiceCollection AddCallingModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString = configuration.GetConnectionString(ConnectionStringName);
        if (string.IsNullOrWhiteSpace(connectionString))
            throw new InvalidOperationException($"Connection string '{ConnectionStringName}' is required for Calling persistence.");
        services.AddDbContext<CallingDbContext>(options => options.UseSqlServer(connectionString,
            sql => sql.MigrationsHistoryTable(MigrationsHistoryTableName, DatabaseSchema)));
        services.AddScoped<ICallActivityService, CallActivityService>();
        services.AddHostedService<CallActivityRecoveryHostedService>();
        services.AddSignalR(options => options.EnableDetailedErrors = false);
        services.AddSingleton<CallSessionRegistry>();
        services.TryAddSingleton(TimeProvider.System);
        services.AddHostedService<CallExpiryBackgroundService>();
        services.AddOptions<CallingIceOptions>()
            .Bind(configuration.GetSection(CallingIceOptions.SectionName))
            .Validate(
                options => options.CredentialLifetimeMinutes is >= 5 and <= 1440,
                "Calling ICE credential lifetime must be between 5 and 1440 minutes.")
            .ValidateOnStart();
        services.AddSingleton<CallingIceConfigurationProvider>();
        return services;
    }

    public static IEndpointRouteBuilder MapCallingModule(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapHub<CallingHub>("/hubs/calling");
        endpoints.MapGet(
                "/api/mobile/calling/participants",
                ListCallableParticipantsAsync)
            .RequireAuthorization(
                policy =>
                    policy
                        .AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme)
                        .RequireAuthenticatedUser())
            .WithName("ListCallableParticipants")
            .WithTags("Mobile Calling")
            .WithSummary("List active callable participants in the authenticated application.");
        if (endpoints.ServiceProvider.GetService<IServiceProviderIsService>()?.IsService(typeof(ICallActivityService)) == true)
            MapActivityEndpoints(endpoints);
        return endpoints;
    }

    private static void MapActivityEndpoints(IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/mobile/calling")
            .RequireAuthorization(policy => policy.AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme).RequireAuthenticatedUser())
            .WithTags("Mobile Calling Activity");
        group.MapGet("/history", async (ClaimsPrincipal principal, int? page, int? pageSize, ICallActivityService service, CancellationToken ct) =>
        {
            var identity = TryIdentity(principal); if (identity is null) return Results.Unauthorized();
            return Results.Ok(await service.ListAsync(identity.Value.ApplicationKey, identity.Value.MembershipId, page ?? 1, pageSize ?? 20, ct));
        });
        group.MapGet("/history/{callId:guid}", async (Guid callId, ClaimsPrincipal principal, ICallActivityService service, CancellationToken ct) =>
        {
            var identity = TryIdentity(principal); if (identity is null) return Results.Unauthorized();
            var detail = await service.DetailAsync(identity.Value.ApplicationKey, identity.Value.MembershipId, callId, ct);
            return detail is null ? Results.NotFound() : Results.Ok(detail);
        });
        group.MapPut("/history/{callId:guid}/usage", async (Guid callId, FinalizeCallUsageRequest request, ClaimsPrincipal principal, ICallActivityService service, CancellationToken ct) =>
        {
            var identity = TryIdentity(principal); if (identity is null) return Results.Unauthorized();
            var result = await service.FinalizeUsageAsync(identity.Value.ApplicationKey, identity.Value.MembershipId, callId,
                new UsageSummary(request.BytesSent, request.BytesReceived, request.ConnectedDurationSeconds), ct);
            return result.Accepted ? Results.Ok(result) : result.Conflict ? Results.Conflict(result) : Results.BadRequest(result);
        });
        group.MapGet("/usage/current", async (ClaimsPrincipal principal, ICallActivityService service, CancellationToken ct) =>
        {
            var identity = TryIdentity(principal); if (identity is null) return Results.Unauthorized();
            return Results.Ok(await service.CurrentPeriodAsync(identity.Value.ApplicationKey, identity.Value.MembershipId, ct));
        });
        group.MapPost("/usage/reset", async (ClaimsPrincipal principal, ICallActivityService service, CancellationToken ct) =>
        {
            var identity = TryIdentity(principal); if (identity is null) return Results.Unauthorized();
            return Results.Ok(await service.ResetAsync(identity.Value.ApplicationKey, identity.Value.MembershipId, ct));
        });
        group.MapPut("/usage/reset-schedule", async (ScheduleUsageResetRequest request, ClaimsPrincipal principal, ICallActivityService service, CancellationToken ct) =>
        {
            var identity = TryIdentity(principal); if (identity is null) return Results.Unauthorized();
            try { return Results.Ok(await service.ScheduleResetAsync(identity.Value.ApplicationKey, identity.Value.MembershipId, request.LocalDateTime, request.TimeZoneId, ct)); }
            catch (ArgumentException error) { return Results.BadRequest(new { error = error.Message }); }
        });
    }

    private static (string ApplicationKey, Guid MembershipId)? TryIdentity(ClaimsPrincipal principal)
    {
        var key = principal.FindFirstValue(ApplicationIdentityDefaults.ApplicationKeyClaim);
        return !string.IsNullOrWhiteSpace(key) && Guid.TryParse(principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim), out var id)
            ? (key, id) : null;
    }

    private static async Task<IResult> ListCallableParticipantsAsync(
        ClaimsPrincipal principal,
        ICallingParticipantDirectory directory,
        IServiceProvider services,
        CancellationToken cancellationToken)
    {
        var applicationKey = principal.FindFirstValue(
            ApplicationIdentityDefaults.ApplicationKeyClaim);
        if (string.IsNullOrWhiteSpace(applicationKey) ||
            !Guid.TryParse(
                principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim),
                out var currentMembershipId))
        {
            return Results.Unauthorized();
        }

        var participants = await directory.ListCallableAsync(
            applicationKey,
            currentMembershipId,
            cancellationToken);
        var reachability = services.GetService<ICallingReachabilityResolver>();
        var reachable = reachability is null ? new HashSet<Guid>() : await reachability.FindReachableMembershipsAsync(
            applicationKey, participants, cancellationToken);
        var sessions = services.GetService<CallSessionRegistry>();

        return Results.Ok(
            participants.Select(participant =>
                new CallableParticipantResult(
                    participant.MembershipId,
                    participant.DisplayName,
                    (sessions?.IsOnline(participant.MembershipId, applicationKey) == true
                        ? CallingParticipantAvailability.Online
                        : reachable.Contains(participant.MembershipId)
                            ? CallingParticipantAvailability.Reachable
                            : CallingParticipantAvailability.Offline).ToString())));
    }
}
