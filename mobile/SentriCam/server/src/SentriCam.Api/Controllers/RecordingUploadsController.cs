using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using Asp.Versioning;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SentriCam.Application.Recordings;
using SentriCam.Application.Common;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.Recordings;
using SentriCam.Domain.Common;

namespace SentriCam.Api.Controllers;

[ApiController]
[ApiVersion(1.0)]
[Authorize(Policy = AuthorizationPolicies.Device)]
[Route("api/v{version:apiVersion}/recordings/uploads")]
public sealed class RecordingUploadsController(
    IRecordingService recordings,
    ILogger<RecordingUploadsController>? logger = null) : ControllerBase
{
    private static readonly Action<ILogger, Guid, Guid, string, bool, Exception?> LogAccepted =
        LoggerMessage.Define<Guid, Guid, string, bool>(
            LogLevel.Information,
            new EventId(1, "RecordingUploadAccepted"),
            "Recording upload accepted device={DeviceId} recording={RecordingId} client={ClientRecordingId} duplicate={Duplicate}");

    [HttpHead("{clientRecordingId}")]
    public async Task<IActionResult> ExistsAsync(
        string clientRecordingId,
        CancellationToken cancellationToken)
    {
        var existing = await recordings.FindUploadAsync(
            RequireDeviceId(),
            clientRecordingId,
            cancellationToken);
        if (existing is null)
        {
            return NotFound();
        }
        Response.Headers.Append("X-SentriCam-Recording-Id", existing.RecordingId.ToString());
        Response.Headers.Append("X-SentriCam-Checksum-SHA256", existing.ChecksumSha256);
        return Ok();
    }

    [HttpPost]
    [Consumes("multipart/form-data")]
    [ProducesResponseType<RecordingUploadResult>(StatusCodes.Status200OK)]
    [ProducesResponseType<RecordingUploadResult>(StatusCodes.Status201Created)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status409Conflict)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status413PayloadTooLarge)]
    public async Task<ActionResult<RecordingUploadResult>> UploadAsync(
        [FromForm] RecordingUploadForm form,
        CancellationToken cancellationToken)
    {
        if (form.Motion == form.Manual)
        {
            throw new DomainValidationException(
                "Exactly one of Motion or Manual must classify the recording.");
        }
        await using var content = form.File.OpenReadStream();
        var result = await recordings.UploadAsync(
            new UploadRecording(
                RequireDeviceId(),
                form.ClientRecordingId,
                form.SessionId,
                form.File.FileName,
                string.IsNullOrWhiteSpace(form.File.ContentType) ? "video/mp4" : form.File.ContentType,
                form.DurationMilliseconds,
                form.SizeBytes,
                form.CreatedUtc,
                form.Motion,
                form.Manual,
                form.ChecksumSha256,
                content),
            cancellationToken);
        if (logger is not null)
        {
            LogAccepted(
                logger,
                result.Recording.DeviceId,
                result.Recording.RecordingId,
                form.ClientRecordingId,
                result.Duplicate,
                null);
        }
        return result.Duplicate
            ? Ok(result)
            : StatusCode(StatusCodes.Status201Created, result);
    }

    private Guid RequireDeviceId()
    {
        var value = User.FindFirstValue(AuthenticationClaimNames.DeviceId);
        if (!Guid.TryParse(value, out var deviceId))
        {
            throw new AccessDeniedException("The device identity claim is unavailable.");
        }
        return deviceId;
    }
}

public sealed class RecordingUploadForm
{
    [Required, StringLength(128)]
    public string ClientRecordingId { get; init; } = string.Empty;

    [Required, StringLength(128)]
    public string SessionId { get; init; } = string.Empty;

    [Range(0, long.MaxValue)]
    public long DurationMilliseconds { get; init; }

    [Range(1, long.MaxValue)]
    public long SizeBytes { get; init; }

    public DateTimeOffset CreatedUtc { get; init; }

    public bool Motion { get; init; }

    public bool Manual { get; init; }

    [Required, RegularExpression("^[0-9a-fA-F]{64}$")]
    public string ChecksumSha256 { get; init; } = string.Empty;

    [Required]
    public IFormFile File { get; init; } = null!;
}
