using System.Security.Claims;
using BotGlobal.Communication.Application.Abstractions;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace BotGlobal.Communication.Endpoints;

public sealed record SendRealtimeTestMessageRequest(
    string TargetUserId,
    string Text);

public static class CommunicationTestEndpoints
{
    public static IEndpointRouteBuilder MapCommunicationTestEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        // This bypasses normal recipient policy solely to verify local realtime
        // transport. Never expose it in Production, Staging, or an unknown host.
        if (!endpoints.ServiceProvider.GetRequiredService<IHostEnvironment>()
                .IsDevelopment())
        {
            return endpoints;
        }

        var group = endpoints
            .MapGroup("/api/communication/test")
            .RequireAuthorization()
            .WithTags("Communication Test");

        group.MapPost(
                "/send-to-user",
                SendToUserAsync)
            .WithName("CommunicationTestSendToUser")
            .WithSummary(
                "Send a non-persisted realtime test message to one connected SignalR user.");

        return endpoints;
    }

    private static async Task<IResult> SendToUserAsync(
        SendRealtimeTestMessageRequest request,
        ClaimsPrincipal user,
        ICommunicationDelivery delivery,
        CancellationToken cancellationToken)
    {
        var senderUserId =
            user.FindFirstValue(ClaimTypes.NameIdentifier)
            ?? user.FindFirstValue("sub");

        if (string.IsNullOrWhiteSpace(senderUserId))
        {
            return Results.Unauthorized();
        }

        if (string.IsNullOrWhiteSpace(request.TargetUserId))
        {
            return Results.ValidationProblem(
                new Dictionary<string, string[]>
                {
                    [nameof(request.TargetUserId)] =
                    [
                        "TargetUserId is required."
                    ]
                });
        }

        if (string.IsNullOrWhiteSpace(request.Text))
        {
            return Results.ValidationProblem(
                new Dictionary<string, string[]>
                {
                    [nameof(request.Text)] =
                    [
                        "Text is required."
                    ]
                });
        }

        try
        {
            var result =
                await delivery.SendTestMessageToUserAsync(
                    senderUserId,
                    request.TargetUserId,
                    request.Text,
                    cancellationToken);

            return Results.Ok(result);
        }
        catch (ArgumentException exception)
        {
            return Results.ValidationProblem(
                new Dictionary<string, string[]>
                {
                    ["request"] =
                    [
                        exception.Message
                    ]
                });
        }
    }
}
