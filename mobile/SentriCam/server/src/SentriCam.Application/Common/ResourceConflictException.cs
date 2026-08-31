namespace SentriCam.Application.Common;

public sealed class ResourceConflictException(string message) : Exception(message);

public sealed class PayloadTooLargeException(string message) : Exception(message);
