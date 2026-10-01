using System.Threading.RateLimiting;
using Microsoft.AspNetCore.RateLimiting;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Api.Security;

public static class RateLimitingExtensions
{
    public static IServiceCollection AddSentriCamRateLimiting(this IServiceCollection services)
    {
        ArgumentNullException.ThrowIfNull(services);

        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            options.AddPolicy(
                SecurityRateLimitPolicies.DeviceRegistration,
                context => CreateIpPartition(context, permitLimit: 10));
            options.AddPolicy(
                SecurityRateLimitPolicies.SignalRConnections,
                context => CreateIpPartition(context, permitLimit: 30));
        });
        return services;
    }

    private static RateLimitPartition<string> CreateIpPartition(
        HttpContext context,
        int permitLimit) =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = permitLimit,
                QueueLimit = 0,
                Window = TimeSpan.FromMinutes(1),
                AutoReplenishment = true,
            });
}
