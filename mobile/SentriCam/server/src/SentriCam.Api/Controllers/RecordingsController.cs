using Asp.Versioning;
using System.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SentriCam.Application.Recordings;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.Recordings;

namespace SentriCam.Api.Controllers;

[ApiController]
[ApiVersion(1.0)]
[Authorize(Policy = AuthorizationPolicies.DeviceControl)]
[Route("api/v{version:apiVersion}/recordings")]
public sealed class RecordingsController(IRecordingService recordings) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType<PagedRecordingResult>(StatusCodes.Status200OK)]
    public async Task<ActionResult<PagedRecordingResult>> ListAsync(
        [FromQuery] Guid? deviceId,
        [FromQuery] Guid[]? deviceIds,
        [FromQuery] string? search,
        [FromQuery] RecordingTriggerFilter trigger = RecordingTriggerFilter.All,
        [FromQuery] RecordingSourceFilter source = RecordingSourceFilter.All,
        [FromQuery] DateTimeOffset? dateFromUtc = null,
        [FromQuery] DateTimeOffset? dateToUtc = null,
        [FromQuery] DateOnly? exactDayUtc = null,
        [FromQuery] int? hourFrom = null,
        [FromQuery] int? hourTo = null,
        [FromQuery] long? minimumDurationMilliseconds = null,
        [FromQuery] long? maximumDurationMilliseconds = null,
        [FromQuery] long? minimumSizeBytes = null,
        [FromQuery] long? maximumSizeBytes = null,
        [FromQuery] RecordingUploadState? uploadState = null,
        [FromQuery] RecordingThumbnailState? thumbnailState = null,
        [FromQuery] RecordingSort sort = RecordingSort.Newest,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 24,
        CancellationToken cancellationToken = default) =>
        Ok(await recordings.ListAsync(
            new RecordingQuery(
                MergeDeviceIds(deviceId, deviceIds),
                search,
                MapSource(source, trigger),
                dateFromUtc,
                dateToUtc,
                exactDayUtc,
                hourFrom,
                hourTo,
                minimumDurationMilliseconds,
                maximumDurationMilliseconds,
                minimumSizeBytes,
                maximumSizeBytes,
                uploadState,
                thumbnailState,
                sort,
                page,
                pageSize),
            cancellationToken));

    [HttpGet("time")]
    [ProducesResponseType<IReadOnlyList<RecordingTimeNode>>(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<RecordingTimeNode>>> AggregateTimeAsync(
        [FromQuery] RecordingTimeLevel level,
        [FromQuery] DateTimeOffset? parentStartUtc,
        [FromQuery] DateTimeOffset? parentEndUtc,
        [FromQuery] Guid? deviceId,
        [FromQuery] Guid[]? deviceIds,
        [FromQuery] string? search,
        [FromQuery] RecordingSourceFilter source = RecordingSourceFilter.All,
        [FromQuery] DateTimeOffset? dateFromUtc = null,
        [FromQuery] DateTimeOffset? dateToUtc = null,
        [FromQuery] int? hourFrom = null,
        [FromQuery] int? hourTo = null,
        [FromQuery] long? minimumDurationMilliseconds = null,
        [FromQuery] long? maximumDurationMilliseconds = null,
        [FromQuery] long? minimumSizeBytes = null,
        [FromQuery] long? maximumSizeBytes = null,
        [FromQuery] RecordingThumbnailState? thumbnailState = null,
        CancellationToken cancellationToken = default) =>
        Ok(await recordings.AggregateTimeAsync(
            new RecordingTimeQuery(
                level,
                parentStartUtc,
                parentEndUtc,
                MergeDeviceIds(deviceId, deviceIds),
                search,
                source,
                dateFromUtc,
                dateToUtc,
                hourFrom,
                hourTo,
                minimumDurationMilliseconds,
                maximumDurationMilliseconds,
                minimumSizeBytes,
                maximumSizeBytes,
                thumbnailState),
            cancellationToken));

    [HttpGet("{recordingId:guid}/content")]
    public async Task<IActionResult> ContentAsync(
        Guid recordingId,
        CancellationToken cancellationToken)
    {
        var content = await recordings.OpenContentAsync(recordingId, cancellationToken);
        ApplyMediaCaching(content, TimeSpan.Zero);
        return File(content.Content, content.ContentType, enableRangeProcessing: true);
    }

    [HttpGet("{recordingId:guid}/download")]
    public async Task<IActionResult> DownloadAsync(
        Guid recordingId,
        CancellationToken cancellationToken)
    {
        var content = await recordings.OpenContentAsync(recordingId, cancellationToken);
        ApplyMediaCaching(content, TimeSpan.Zero);
        return File(content.Content, content.ContentType, content.FileName, enableRangeProcessing: true);
    }

    [HttpGet("{recordingId:guid}/thumbnail")]
    public async Task<IActionResult> ThumbnailAsync(
        Guid recordingId,
        CancellationToken cancellationToken)
    {
        var content = await recordings.OpenThumbnailAsync(recordingId, cancellationToken);
        ApplyMediaCaching(content, TimeSpan.FromDays(1));
        return File(content.Content, content.ContentType);
    }

    [HttpPost("{recordingId:guid}/thumbnail/regenerate")]
    [ProducesResponseType(StatusCodes.Status202Accepted)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> RegenerateThumbnailAsync(
        Guid recordingId,
        CancellationToken cancellationToken)
    {
        await recordings.RequestThumbnailRegenerationAsync(recordingId, cancellationToken);
        return Accepted();
    }

    [HttpDelete("{recordingId:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DeleteAsync(
        Guid recordingId,
        CancellationToken cancellationToken)
    {
        await recordings.DeleteAsync(recordingId, cancellationToken);
        return NoContent();
    }

    private static Guid[]? MergeDeviceIds(Guid? deviceId, Guid[]? deviceIds)
    {
        var merged = (deviceIds ?? [])
            .Concat(deviceId is null ? [] : [deviceId.Value])
            .Distinct()
            .ToArray();
        return merged.Length == 0 ? null : merged;
    }

    private static RecordingSourceFilter MapSource(
        RecordingSourceFilter source,
        RecordingTriggerFilter trigger) =>
        source != RecordingSourceFilter.All
            ? source
            : trigger switch
            {
                RecordingTriggerFilter.Motion => RecordingSourceFilter.Motion,
                RecordingTriggerFilter.Manual => RecordingSourceFilter.Manual,
                _ => RecordingSourceFilter.All,
            };

    private void ApplyMediaCaching(RecordingContent content, TimeSpan maximumAge)
    {
        Response.Headers.CacheControl = $"private, max-age={(int)maximumAge.TotalSeconds}";
        if (content.LastModifiedUtc is not null)
        {
            Response.Headers.LastModified = content.LastModifiedUtc.Value.ToUniversalTime().ToString("R", CultureInfo.InvariantCulture);
        }
        if (content.EntityTag is not null)
        {
            Response.Headers.ETag = content.EntityTag;
        }
    }
}
