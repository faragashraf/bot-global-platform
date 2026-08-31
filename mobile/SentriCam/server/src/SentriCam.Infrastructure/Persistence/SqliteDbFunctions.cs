namespace SentriCam.Infrastructure.Persistence;

internal static class SqliteDbFunctions
{
    public static string Strftime(string format, DateTimeOffset value) =>
        throw new InvalidOperationException("This function can only be evaluated by SQLite.");
}
