namespace SentriCam.Application.Common;

public sealed class ResourceNotFoundException(string resourceName, object resourceId)
    : Exception($"{resourceName} '{resourceId}' was not found.")
{
    public string ResourceName { get; } = resourceName;

    public object ResourceId { get; } = resourceId;
}
