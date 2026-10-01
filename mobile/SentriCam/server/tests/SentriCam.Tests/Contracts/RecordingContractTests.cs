using System.Text.Json;
using SentriCam.Contracts.Recordings;

namespace SentriCam.Tests.Contracts;

public sealed class RecordingContractTests
{
    [Fact]
    public void RecordingEnumsSerializeAsStableNamesForWebClients()
    {
        var json = JsonSerializer.Serialize(new
        {
            UploadState = RecordingUploadState.Completed,
            ThumbnailState = RecordingThumbnailState.Failed,
            ChildrenLevel = RecordingTimeLevel.Month,
        });

        Assert.Contains("\"UploadState\":\"Completed\"", json, StringComparison.Ordinal);
        Assert.Contains("\"ThumbnailState\":\"Failed\"", json, StringComparison.Ordinal);
        Assert.Contains("\"ChildrenLevel\":\"Month\"", json, StringComparison.Ordinal);
    }
}
