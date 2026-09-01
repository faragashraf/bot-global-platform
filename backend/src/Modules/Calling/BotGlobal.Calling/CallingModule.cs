using System.Security.Claims;
using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace BotGlobal.Calling;

public static class CallingModule
{
    public static IServiceCollection AddCallingModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
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
        return endpoints;
    }

    private static async Task<IResult> ListCallableParticipantsAsync(
        ClaimsPrincipal principal,
        ICallingParticipantDirectory directory,
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

        return Results.Ok(
            participants.Select(participant =>
                new CallableParticipantResult(
                    participant.MembershipId,
                    participant.DisplayName)));
    }
}
