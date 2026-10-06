using System.Threading.RateLimiting;
using System.Security.Claims;
using BotGlobal.Calling.Realtime;
using BotGlobal.Calling.Application;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Persistence;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Options;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Calling;

public static class CallingModule
{
    public const string ConnectionStringName = "Communication";
    public const string DatabaseSchema = "calling";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";
    public const string NqrbContactInviteRateLimitPolicy = "nqrb-contact-invites";
    public const string NqrbContactSearchRateLimitPolicy = "nqrb-contact-search";
    public const string NqrbGuestCallInviteRateLimitPolicy = "nqrb-guest-call-invites";
    public static IServiceCollection AddCallingModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString = configuration.GetConnectionString(ConnectionStringName);
        if (string.IsNullOrWhiteSpace(connectionString))
            throw new InvalidOperationException($"Connection string '{ConnectionStringName}' is required for Calling persistence.");
        services.AddDbContext<CallingDbContext>(options => options.UseBotGlobalDatabase(
            configuration,
            ConnectionStringName,
            connectionString,
            DatabaseSchema,
            MigrationsHistoryTableName));
        services.TryAddScoped<
            IApplicationMembershipActivityReader,
            UnavailableApplicationMembershipActivityReader>();
        services.AddScoped<ICallActivityService, CallActivityService>();
        services.AddScoped<INqrbCallEligibilityService, NqrbCallEligibilityService>();
        services.AddScoped<INqrbContactBookService, NqrbContactBookService>();
        services.AddScoped<INqrbBlockService, NqrbBlockService>();
        services.AddSingleton<NqrbGuestCallInviteService>();
        services.AddScoped<CallingAccountDataEraser>();
        services.AddScoped<IApplicationAccountDeletionHandler, CallingAccountDeletionHandler>();
        services.AddRateLimiter(options =>
        {
            options.AddPolicy(
                NqrbContactInviteRateLimitPolicy,
                context =>
                {
                    var membershipId = context.User.FindFirstValue(
                        ApplicationIdentityDefaults.MembershipIdClaim);
                    return RateLimitPartition.GetFixedWindowLimiter(
                        string.IsNullOrWhiteSpace(membershipId) ? "anonymous" : membershipId,
                        _ => new FixedWindowRateLimiterOptions
                        {
                            PermitLimit = 12,
                            Window = TimeSpan.FromMinutes(1),
                            QueueLimit = 0,
                            AutoReplenishment = true
                        });
                });
            options.AddPolicy(
                NqrbContactSearchRateLimitPolicy,
                context => RateLimitPartition.GetFixedWindowLimiter(
                    context.User.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim) ?? "anonymous",
                    _ => new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = 30,
                        Window = TimeSpan.FromMinutes(1),
                        QueueLimit = 0,
                        AutoReplenishment = true
                    }));
            options.AddPolicy(
                NqrbGuestCallInviteRateLimitPolicy,
                context =>
                {
                    var partition = context.User.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim)
                        ?? context.Connection.RemoteIpAddress?.ToString()
                        ?? "anonymous";
                    return RateLimitPartition.GetFixedWindowLimiter(
                        partition,
                        _ => new FixedWindowRateLimiterOptions
                        {
                            PermitLimit = 10,
                            Window = TimeSpan.FromMinutes(1),
                            QueueLimit = 0,
                            AutoReplenishment = true
                        });
                });
        });
        services.AddAuthentication()
            .AddScheme<AuthenticationSchemeOptions, NqrbGuestCallAuthenticationHandler>(
                NqrbGuestCallAuthenticationDefaults.Scheme,
                _ => { });
        if (!BotGlobalDatabaseOptions.IsCanaryEnsureCreatedEnabled(configuration))
        {
            services.AddHostedService<CallActivityRecoveryHostedService>();
        }

        services.AddSignalR(options => options.EnableDetailedErrors = false);
        services.AddSingleton<CallSessionRegistry>();
        services.TryAddSingleton(TimeProvider.System);
        if (!BotGlobalDatabaseOptions.IsCanaryEnsureCreatedEnabled(configuration))
        {
            services.AddHostedService<CallExpiryBackgroundService>();
        }

        services.AddOptions<CallingIceOptions>()
            .Bind(configuration.GetSection(CallingIceOptions.SectionName))
            .Validate(
                options => options.CredentialLifetimeMinutes is >= 5 and <= 1440,
                "Calling ICE credential lifetime must be between 5 and 1440 minutes.")
            .Validate(
                options => !options.Cloudflare.Enabled ||
                           (!string.IsNullOrWhiteSpace(options.Cloudflare.KeyId) &&
                            !string.IsNullOrWhiteSpace(options.Cloudflare.ApiToken)),
                "Cloudflare TURN requires both a key ID and an API token when enabled.")
            .Validate(
                options => !options.Cloudflare.Enabled ||
                           options.Cloudflare.CredentialLifetimeSeconds is >= 60 and <= 86400,
                "Cloudflare TURN credential lifetime must be between 60 and 86400 seconds.")
            .Validate(
                options => !options.Cloudflare.Enabled ||
                           options.Cloudflare.RequestTimeoutSeconds is >= 1 and <= 30,
                "Cloudflare TURN request timeout must be between 1 and 30 seconds.")
            .Validate(
                options => !options.Cloudflare.Enabled ||
                           Uri.TryCreate(options.Cloudflare.EndpointBaseUrl, UriKind.Absolute, out var endpoint) &&
                           (endpoint.Scheme == Uri.UriSchemeHttps || endpoint.IsLoopback),
                "Cloudflare TURN endpoint must be an absolute HTTPS URI, except loopback test endpoints.")
            .ValidateOnStart();
        services.AddOptions<NqrbGuestCallInviteOptions>()
            .Bind(configuration.GetSection(NqrbGuestCallInviteOptions.SectionName))
            .Validate(
                options => options.InviteLifetimeMinutes is >= 1 and <= 60,
                "NQRB guest call invite lifetime must be between 1 and 60 minutes.")
            .Validate(
                options => options.RingLifetimeSeconds is >= 15 and <= 120,
                "NQRB guest call ring lifetime must be between 15 and 120 seconds.")
            .Validate(
                options => NqrbGuestCallLink.IsValidPublicPageUrl(options.PublicPageUrl),
                "NQRB guest call public page URL must be the HTTPS /guest-call page without credentials, query, or fragment.")
            .ValidateOnStart();
        services.AddSingleton(sp => new CallingIceConfigurationProvider(
            sp.GetRequiredService<IOptions<CallingIceOptions>>(),
            sp.GetRequiredService<TimeProvider>(),
            new HttpClient { Timeout = Timeout.InfiniteTimeSpan }));
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
        MapNqrbContactEndpoints(endpoints);
        MapNqrbGuestCallEndpoints(endpoints);
        return endpoints;
    }

    private static void MapNqrbGuestCallEndpoints(IEndpointRouteBuilder endpoints)
    {
        var hostGroup = endpoints.MapGroup("/api/mobile/nqrb/guest-call-invites")
            .RequireAuthorization(ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb))
            .RequireRateLimiting(NqrbGuestCallInviteRateLimitPolicy)
            .WithTags("Mobile NQRB Guest Calls");

        hostGroup.MapPost("/", (
            ClaimsPrincipal principal,
            HttpRequest request,
            [FromServices] NqrbGuestCallInviteService invites,
            [FromServices] IOptions<NqrbGuestCallInviteOptions> options) =>
        {
            var identity = TryNqrbApplicationIdentity(principal);
            if (identity is null) return Results.Unauthorized();
            var created = invites.CreateHostInvite(identity);
            return Results.Ok(new NqrbGuestCallInviteCreateResponse(
                created.InviteId,
                NqrbGuestCallLink.Build(request, created.Capability, options.Value.PublicPageUrl),
                created.ExpiresAtUtc));
        })
        .WithName("CreateNqrbGuestCallInvite")
        .WithSummary("Create a short-lived one-seat browser guest call invitation.");

        hostGroup.MapDelete("/{inviteId:guid}", (
            Guid inviteId,
            ClaimsPrincipal principal,
            [FromServices] NqrbGuestCallInviteService invites) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null || IsGuestIdentity(principal)) return Results.Unauthorized();
            return invites.RevokeHostInvite(identity.Value.MembershipId, inviteId) switch
            {
                NqrbGuestCallInviteStatus.Revoked => Results.NoContent(),
                NqrbGuestCallInviteStatus.AlreadyUsed => Results.Conflict(new { code = "nqrb_guest_call_invite_used" }),
                _ => Results.NotFound(new { code = "nqrb_guest_call_invite_unavailable" })
            };
        })
        .WithName("RevokeNqrbGuestCallInvite")
        .WithSummary("Revoke a pending NQRB browser guest call invitation.");

        endpoints.MapGet("/nqrb/guest-call", () =>
            Results.Content(NqrbGuestCallPage.Html(), "text/html; charset=utf-8"))
            .AllowAnonymous()
            .WithName("NqrbGuestCallPage")
            .WithTags("NQRB Guest Calls");

        var publicGroup = endpoints.MapGroup("/api/public/nqrb/guest-call-invites")
            .RequireRateLimiting(NqrbGuestCallInviteRateLimitPolicy)
            .WithTags("Public NQRB Guest Calls");

        publicGroup.MapPost("/preview", (
            NqrbGuestCallCapabilityRequest request,
            [FromServices] NqrbGuestCallInviteService invites) =>
        {
            var result = invites.Preview(request.Capability, request.ClientRequestId);
            return result.Status switch
            {
                NqrbGuestCallInviteStatus.Created => Results.Ok(result.Value),
                NqrbGuestCallInviteStatus.Expired => Results.StatusCode(StatusCodes.Status410Gone),
                NqrbGuestCallInviteStatus.Revoked => Results.StatusCode(StatusCodes.Status410Gone),
                NqrbGuestCallInviteStatus.Rejected => Results.Conflict(new { code = "nqrb_guest_call_rejected" }),
                NqrbGuestCallInviteStatus.Cancelled => Results.Conflict(new { code = "nqrb_guest_call_cancelled" }),
                _ => Results.NotFound(new { code = "nqrb_guest_call_invite_unavailable" })
            };
        })
        .AllowAnonymous()
        .WithName("PreviewNqrbGuestCallInvite")
        .WithSummary("Preview the host display name for a browser guest call invitation.");

        publicGroup.MapPost("/join", async (
            NqrbGuestCallJoinRequest join,
            HttpContext context,
            [FromServices] NqrbGuestCallInviteService invites,
            [FromServices] CallSessionRegistry sessions,
            [FromServices] IHubContext<CallingHub> hub,
            [FromServices] ICallActivityService activity,
            [FromServices] IIncomingCallNotificationDispatcher notifications,
            [FromServices] ILogger<CallingHub> logger,
            CancellationToken cancellationToken) =>
        {
            var result = invites.Accept(join.Capability, join.DisplayName ?? string.Empty, join.MicrophoneConsent, join.ClientRequestId);
            if (result.Value is not null)
            {
                var accepted = result.Value;
                if (accepted.IsNew)
                {
                    var session = sessions.RequireGuestInviteSession(
                        accepted.CallId, accepted.InviteId, accepted.HostMembershipId);
                    try
                    {
                        await activity.StartAsync(session, cancellationToken);
                    }
                    catch (Exception error)
                    {
                        sessions.CancelGuestInvite(accepted.CallId, accepted.InviteId);
                        invites.Complete(accepted.InviteId);
                        try { await activity.FinishAsync(session, DateTimeOffset.UtcNow, CancellationToken.None); }
                        catch (Exception finishError)
                        {
                            logger.LogWarning("Guest call history rollback failed. ErrorType={ErrorType}", finishError.GetType().Name);
                        }
                        logger.LogWarning("Guest call could not start. ErrorType={ErrorType}", error.GetType().Name);
                        if (error is OperationCanceledException) throw;
                        return Results.StatusCode(StatusCodes.Status503ServiceUnavailable);
                    }
                    foreach (var connection in sessions.ConnectedParticipants(accepted.HostMembershipId, BotGlobalApplications.Nqrb))
                    {
                        await hub.Clients.Client(connection.ConnectionId).SendAsync(
                            "CallOffered",
                            new CallOfferedEvent(accepted.CallId, BotGlobalApplications.Nqrb,
                                accepted.GuestMembershipId, accepted.GuestDisplayName),
                            cancellationToken);
                    }

                    await notifications.DispatchAsync(new IncomingCallNotification(
                        BotGlobalApplications.Nqrb,
                        accepted.HostSubjectId,
                        accepted.CallId,
                        IncomingCallNotificationKind.Offered,
                        accepted.GuestDisplayName,
                        accepted.ExpiresAtUtc),
                        cancellationToken);
                }

                context.Response.Cookies.Append(
                    NqrbGuestCallAuthenticationDefaults.CookieName,
                    accepted.GuestAccessToken,
                    new CookieOptions
                    {
                        HttpOnly = true,
                        Secure = context.Request.IsHttps,
                        SameSite = SameSiteMode.Strict,
                        Path = $"{context.Request.PathBase}/hubs/calling",
                        MaxAge = accepted.GuestAccessExpiresAtUtc - DateTimeOffset.UtcNow > TimeSpan.Zero
                            ? accepted.GuestAccessExpiresAtUtc - DateTimeOffset.UtcNow
                            : TimeSpan.FromSeconds(1)
                    });
                return Results.Ok(new NqrbGuestCallJoinResponse(
                    accepted.CallId,
                    accepted.HostDisplayName,
                    accepted.ExpiresAtUtc));
            }

            return result.Status switch
            {
                NqrbGuestCallInviteStatus.MicrophoneConsentRequired => Results.BadRequest(new { code = "microphone_consent_required" }),
                NqrbGuestCallInviteStatus.Expired => Results.StatusCode(StatusCodes.Status410Gone),
                NqrbGuestCallInviteStatus.Revoked => Results.StatusCode(StatusCodes.Status410Gone),
                NqrbGuestCallInviteStatus.AlreadyUsed => Results.Conflict(new { code = "nqrb_guest_call_invite_used" }),
                NqrbGuestCallInviteStatus.HostUnavailable => Results.Conflict(new { code = "nqrb_guest_call_host_unavailable" }),
                _ => Results.NotFound(new { code = "nqrb_guest_call_invite_unavailable" })
            };
        })
        .AllowAnonymous()
        .WithName("JoinNqrbGuestCallInvite")
        .WithSummary("Claim a one-seat NQRB guest call invite and ring the host.");
    }

    private static void MapNqrbContactEndpoints(IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/mobile/nqrb")
            .RequireAuthorization(ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb))
            .WithTags("Mobile NQRB Contacts");

        group.MapGet("/blocked-accounts", async (
            ClaimsPrincipal principal,
            [FromServices] INqrbBlockService blocks,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            return identity is null ? Results.Unauthorized() : Results.Ok(
                await blocks.ListAsync(identity.Value.MembershipId, cancellationToken));
        }).WithName("ListNqrbBlockedAccounts");

        group.MapPost("/blocked-accounts/{blockedMembershipId:guid}", async (
            Guid blockedMembershipId,
            ClaimsPrincipal principal,
            [FromServices] INqrbBlockService blocks,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null) return Results.Unauthorized();
            return await blocks.BlockAsync(identity.Value.MembershipId, blockedMembershipId, cancellationToken)
                ? Results.NoContent()
                : Results.NotFound(new { code = "nqrb_contact_unavailable" });
        }).WithName("BlockNqrbAccount");

        group.MapDelete("/blocked-accounts/{blockedMembershipId:guid}", async (
            Guid blockedMembershipId,
            ClaimsPrincipal principal,
            [FromServices] INqrbBlockService blocks,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null) return Results.Unauthorized();
            await blocks.UnblockAsync(identity.Value.MembershipId, blockedMembershipId, cancellationToken);
            return Results.NoContent();
        }).WithName("UnblockNqrbAccount");

        group.MapGet("/contacts", async (
            ClaimsPrincipal principal,
            int? page,
            int? pageSize,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            return Results.Ok(await contacts.ListAsync(
                identity.Value.MembershipId,
                page ?? 1,
                pageSize ?? 20,
                cancellationToken));
        })
        .WithName("ListNqrbContacts")
        .WithSummary("List saved NQRB calling contacts for the authenticated account.");

        group.MapPost("/contacts/{contactMembershipId:guid}", async (
            Guid contactMembershipId,
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            var result = await contacts.AddAsync(
                identity.Value.MembershipId,
                contactMembershipId,
                cancellationToken);
            return result.Status == NqrbContactAddStatus.Unavailable
                ? Results.NotFound(new { code = "nqrb_contact_unavailable" })
                : Results.Ok(result.Contact);
        })
        .WithName("AddNqrbContact")
        .WithSummary("Save an active NQRB account as a one-way calling contact.");

        group.MapPost("/contacts/from-history/{callId:guid}", async (
            Guid callId,
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            var result = await contacts.AddFromCallHistoryAsync(
                identity.Value.MembershipId,
                callId,
                cancellationToken);
            return result.Status == NqrbContactAddStatus.Unavailable
                ? Results.NotFound(new { code = "nqrb_call_contact_unavailable" })
                : Results.Ok(result.Contact);
        })
        .WithName("AddNqrbContactFromCallHistory")
        .WithSummary("Save the authenticated NQRB call counterpart from an authorized call record.");

        group.MapGet("/contacts/{contactMembershipId:guid}", async (
            Guid contactMembershipId,
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
                return Results.Unauthorized();

            var contact = await contacts.FindAsync(identity.Value.MembershipId, contactMembershipId, cancellationToken);
            return contact is null
                ? Results.NotFound(new { code = "nqrb_contact_unavailable" })
                : Results.Ok(contact);
        })
        .WithName("GetNqrbContact")
        .WithSummary("Get one contact saved by the authenticated NQRB account.");

        group.MapDelete("/contacts/{contactMembershipId:guid}", async (
            Guid contactMembershipId,
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            await contacts.RemoveAsync(
                identity.Value.MembershipId,
                contactMembershipId,
                cancellationToken);
            return Results.NoContent();
        })
        .WithName("RemoveNqrbContact")
        .WithSummary("Remove an NQRB account from the authenticated account's saved contacts.");

        group.MapPut("/contacts/{contactMembershipId:guid}/nickname", async (
            Guid contactMembershipId,
            NqrbContactNicknameRequest request,
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
                return Results.Unauthorized();

            var result = await contacts.UpdateNicknameAsync(
                identity.Value.MembershipId,
                contactMembershipId,
                request.Nickname,
                cancellationToken);
            return result.Status switch
            {
                NqrbContactNicknameStatus.Updated => Results.Ok(result.Contact),
                NqrbContactNicknameStatus.Invalid => Results.BadRequest(new { code = "nqrb_contact_nickname_invalid" }),
                _ => Results.NotFound(new { code = "nqrb_contact_unavailable" })
            };
        })
        .WithName("UpdateNqrbContactNickname")
        .WithSummary("Set or clear a private nickname on the authenticated account's saved NQRB contact.");

        group.MapGet("/users/search", async (
            ClaimsPrincipal principal,
            string? query,
            int? page,
            int? pageSize,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            try
            {
                return Results.Ok(await contacts.SearchUsersAsync(
                    identity.Value.MembershipId,
                    query ?? string.Empty,
                    page ?? 1,
                    pageSize ?? 20,
                    cancellationToken));
            }
            catch (ArgumentException error) when (error.ParamName == "query")
            {
                return Results.BadRequest(new { code = "nqrb_user_search_query_invalid" });
            }
        })
        .RequireRateLimiting(NqrbContactSearchRateLimitPolicy)
        .WithName("SearchNqrbUsers")
        .WithSummary("Search active NQRB accounts by explicit query for contact-book additions.");

        group.MapPost("/contact-invites", async (
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            var result = await contacts.CreateInviteAsync(
                identity.Value.MembershipId,
                cancellationToken);
            return result.Status == NqrbContactInviteStatus.Created
                ? Results.Ok(result.Invite)
                : Results.NotFound(new { code = "nqrb_invite_issuer_unavailable" });
        })
        .RequireRateLimiting(NqrbContactInviteRateLimitPolicy)
        .WithName("CreateNqrbContactInvite")
        .WithSummary("Create a single-use NQRB contact invitation code for the authenticated account.");

        group.MapGet("/contact-invites/{code}/preview", async (
            string code,
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            var result = await contacts.PreviewInviteAsync(
                identity.Value.MembershipId,
                code,
                cancellationToken);
            return result.Status switch
            {
                NqrbContactInviteStatus.Created => Results.Ok(result.Preview),
                NqrbContactInviteStatus.SelfInvite => Results.BadRequest(new { code = "nqrb_invite_self" }),
                NqrbContactInviteStatus.Expired => Results.StatusCode(StatusCodes.Status410Gone),
                NqrbContactInviteStatus.AlreadyClaimed => Results.Conflict(new { code = "nqrb_invite_claimed" }),
                _ => Results.NotFound(new { code = "nqrb_invite_unavailable" })
            };
        })
        .RequireRateLimiting(NqrbContactInviteRateLimitPolicy)
        .WithName("PreviewNqrbContactInvite")
        .WithSummary("Preview a NQRB contact invitation by issuer display name only.");

        group.MapPost("/contact-invites/{code}/accept", async (
            string code,
            ClaimsPrincipal principal,
            [FromServices] INqrbContactBookService contacts,
            CancellationToken cancellationToken) =>
        {
            var identity = TryNqrbIdentity(principal);
            if (identity is null)
            {
                return Results.Unauthorized();
            }

            var result = await contacts.AcceptInviteAsync(
                identity.Value.MembershipId,
                code,
                cancellationToken);
            return result.Status switch
            {
                NqrbContactInviteStatus.Accepted => Results.Ok(result.Issuer),
                NqrbContactInviteStatus.SelfInvite => Results.BadRequest(new { code = "nqrb_invite_self" }),
                NqrbContactInviteStatus.Expired => Results.StatusCode(StatusCodes.Status410Gone),
                NqrbContactInviteStatus.AlreadyClaimed => Results.Conflict(new { code = "nqrb_invite_claimed" }),
                _ => Results.NotFound(new { code = "nqrb_invite_unavailable" })
            };
        })
        .RequireRateLimiting(NqrbContactInviteRateLimitPolicy)
        .WithName("AcceptNqrbContactInvite")
        .WithSummary("Accept a single-use NQRB contact invitation and create mutual contacts.");
    }

    private static void MapActivityEndpoints(IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/mobile/calling")
            .RequireAuthorization(policy => policy.AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme).RequireAuthenticatedUser())
            .WithTags("Mobile Calling Activity");
        group.MapGet("/history", async (ClaimsPrincipal principal, int? page, int? pageSize, string? filter, ICallActivityService service, CancellationToken ct) =>
        {
            var identity = TryIdentity(principal); if (identity is null) return Results.Unauthorized();
            if (!TryCallHistoryFilter(filter, out var parsedFilter)) return Results.BadRequest(new { code = "call_history_filter_invalid" });
            return Results.Ok(await service.ListAsync(identity.Value.ApplicationKey, identity.Value.MembershipId, page ?? 1, pageSize ?? 20, parsedFilter, ct));
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

    private static bool TryCallHistoryFilter(string? value, out CallHistoryFilter filter)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            filter = CallHistoryFilter.All;
            return true;
        }

        return Enum.TryParse(value, ignoreCase: true, out filter) &&
            Enum.IsDefined(typeof(CallHistoryFilter), filter);
    }

    private static ApplicationIdentityDescriptor? TryNqrbApplicationIdentity(ClaimsPrincipal principal)
    {
        var identity = TryNqrbIdentity(principal);
        if (identity is null || IsGuestIdentity(principal)) return null;
        var subjectId = principal.FindFirstValue(ClaimTypes.NameIdentifier);
        if (string.IsNullOrWhiteSpace(subjectId)) return null;
        return new ApplicationIdentityDescriptor(
            identity.Value.MembershipId,
            Guid.TryParse(principal.FindFirstValue(ClaimTypes.Sid), out var userId) ? userId : null,
            subjectId,
            BotGlobalApplications.Nqrb,
            principal.Identity?.Name ?? "NQRB host",
            false);
    }

    private static bool IsGuestIdentity(ClaimsPrincipal principal) =>
        string.Equals(principal.FindFirstValue(ApplicationIdentityDefaults.GuestClaim),
            "true", StringComparison.OrdinalIgnoreCase);

    private static (string ApplicationKey, Guid MembershipId)? TryNqrbIdentity(ClaimsPrincipal principal)
    {
        var identity = TryIdentity(principal);
        return identity is not null &&
            string.Equals(identity.Value.ApplicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal) &&
            string.Equals(
                principal.FindFirstValue(ApplicationIdentityDefaults.GuestClaim),
                "false",
                StringComparison.OrdinalIgnoreCase)
                ? identity
                : null;
    }

    private static async Task<IResult> ListCallableParticipantsAsync(
        ClaimsPrincipal principal,
        bool? savedOnly,
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

        IReadOnlyList<CallingParticipantDescriptor> participants;
        if (savedOnly == true && string.Equals(applicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal))
        {
            var contacts = services.GetRequiredService<INqrbContactBookService>();
            participants = await contacts.ListCallableContactsAsync(currentMembershipId, cancellationToken);
        }
        else
        {
            participants = await directory.ListCallableAsync(
                applicationKey,
                currentMembershipId,
                cancellationToken);
        }
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

public sealed record NqrbGuestCallInviteCreateResponse(Guid InviteId, string ShareLink, DateTimeOffset ExpiresAtUtc);
public sealed record NqrbContactNicknameRequest(string? Nickname);
public sealed record NqrbGuestCallCapabilityRequest(string Capability, string? ClientRequestId = null);
public sealed record NqrbGuestCallJoinRequest(string Capability, string? DisplayName, bool MicrophoneConsent, string? ClientRequestId = null);
public sealed record NqrbGuestCallJoinResponse(Guid CallId, string HostDisplayName, DateTimeOffset ExpiresAtUtc);
