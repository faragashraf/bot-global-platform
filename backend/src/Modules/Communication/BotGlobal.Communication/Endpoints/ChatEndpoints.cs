using BotGlobal.Communication.Application.Chat;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Mvc;

namespace BotGlobal.Communication.Endpoints;

public sealed record CreateDirectChatRequest(string Reference);
public sealed record SendChatTextRequest(string ClientMessageId, string Text);
public sealed record ChatVoiceAckRequest(Guid InstallationId, string Sha256, long Length);
public sealed record ChatReadReceiptRequest(long Sequence);

public static class ChatEndpoints
{
    public static IEndpointRouteBuilder MapChatEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/mobile/communications/chat")
            .RequireAuthorization(policy => policy.AddAuthenticationSchemes(
                    ApplicationIdentityDefaults.Scheme, MobileDeviceAuthenticationDefaults.Scheme)
                .RequireAuthenticatedUser())
            .WithTags("Mobile Chat");

        group.MapGet("/context", async (System.Security.Claims.ClaimsPrincipal principal,
            [FromServices] BotGlobal.Contracts.Communication.IChatActorResolver actors, [FromServices] ChatPolicyRegistry policies, CancellationToken token) =>
        {
            var actor = await actors.ResolveAsync(principal, token);
            if (actor is null) return Results.Unauthorized();
            var adapters = policies.Resolve(actor.Application.ApplicationKey);
            if (adapters is null || await adapters.Value.Directory.FindBySubjectAsync(
                    actor.Application, actor.SubjectId, token) is null) return Results.StatusCode(StatusCodes.Status403Forbidden);
            return Results.Ok(new {
                applicationId = actor.Application.ApplicationId,
                subjectId = actor.SubjectId,
                mechanism = actor.Mechanism.ToString(),
                installationId = actor.InstallationId,
            });
        }).WithName("GetChatActorContext");

        group.MapPost("/conversations/direct", async (CreateDirectChatRequest request, [FromServices] IChatEngine engine, CancellationToken token) =>
            await engine.CreateOrGetDirectAsync(request.Reference, token) is { } conversation
                ? Results.Ok(conversation) : Results.StatusCode(StatusCodes.Status403Forbidden)).WithName("CreateOrGetDirectChatConversation");

        group.MapGet("/conversations", async (DateTimeOffset? before, string? cursor, int? take, [FromServices] IChatEngine engine, CancellationToken token) =>
            Results.Ok(await engine.ListConversationsAsync(before, take ?? 30, token, cursor))).WithName("ListChatConversations");

        group.MapGet("/conversations/{conversationId:guid}/messages", async (Guid conversationId, long? afterSequence,
            int? take, [FromServices] IChatEngine engine, CancellationToken token) =>
            await engine.ListMessagesAsync(conversationId, afterSequence ?? 0, take ?? 50, token) is { } page
                ? Results.Ok(page) : Results.NotFound()).WithName("ListChatMessages");

        group.MapPost("/conversations/{conversationId:guid}/messages/text", async (Guid conversationId,
            SendChatTextRequest request, [FromServices] IChatEngine engine, CancellationToken token) =>
            ToSendResult(await engine.SendTextAsync(conversationId, request.ClientMessageId, request.Text, token)))
            .WithName("SendChatTextMessage");

        group.MapPost("/conversations/{conversationId:guid}/messages/voice", async (Guid conversationId,
            HttpRequest request, [FromServices] IChatEngine engine, CancellationToken token) =>
        {
            if (!request.Headers.TryGetValue("X-Client-Message-Id", out var clientId) ||
                !request.Headers.TryGetValue("X-Voice-Duration-Ms", out var duration) ||
                !int.TryParse(duration, out var durationMs)) return Results.BadRequest(new { code = "chat_voice_headers_invalid" });
            try
            {
                return ToSendResult(await engine.SendVoiceAsync(conversationId, clientId.ToString(), request.Body,
                    request.ContentType ?? string.Empty, durationMs, token));
            }
            catch (InvalidDataException error) { return Results.BadRequest(new { code = error.Message }); }
        }).DisableAntiforgery().WithName("SendChatVoiceMessage");

        group.MapGet("/voice/{transferId:guid}", async Task<IResult> (Guid transferId, [FromServices] IChatEngine engine, CancellationToken token) =>
        {
            var result = await engine.DownloadVoiceAsync(transferId, token);
            if (result is null) return Results.NotFound();
            return Results.Stream(result.Value.Content, "audio/mp4", enableRangeProcessing: false);
        }).WithName("DownloadChatVoice");

        group.MapPost("/voice/{transferId:guid}/durable-download", async (Guid transferId, ChatVoiceAckRequest request,
            [FromServices] IChatEngine engine, CancellationToken token) =>
            await engine.AcknowledgeVoiceAsync(transferId, request.InstallationId, request.Sha256, request.Length, token)
                ? Results.NoContent() : Results.Conflict(new { code = "chat_voice_ack_rejected" }))
            .WithName("AcknowledgeDurableChatVoiceDownload");

        group.MapPut("/conversations/{conversationId:guid}/read", async (Guid conversationId, ChatReadReceiptRequest request,
            [FromServices] IChatEngine engine, CancellationToken token) =>
            await engine.AdvanceReadReceiptAsync(conversationId, request.Sequence, token) is { } sequence
                ? Results.Ok(new { lastReadSequence = sequence }) : Results.Conflict(new { code = "chat_read_receipt_rejected" }))
            .WithName("AdvanceChatReadReceipt");
        return endpoints;
    }

    private static IResult ToSendResult(ChatSendResult result) => result switch
    {
        { Message: not null } => Results.Ok(result.Message),
        { Forbidden: true } => Results.StatusCode(StatusCodes.Status403Forbidden),
        _ => Results.Conflict(new { code = "chat_client_message_conflict" }),
    };
}
