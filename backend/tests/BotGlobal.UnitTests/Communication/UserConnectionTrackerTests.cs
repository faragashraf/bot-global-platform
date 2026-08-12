using BotGlobal.Communication.Realtime;

namespace BotGlobal.UnitTests.Communication;

public sealed class UserConnectionTrackerTests
{
    [Fact]
    public void Connected_FirstConnection_ReturnsTrueAndUserIsOnline()
    {
        var tracker = new UserConnectionTracker();

        var becameOnline = tracker.Connected("user-1", "connection-1");

        Assert.True(becameOnline);
        Assert.True(tracker.IsOnline("user-1"));
    }

    [Fact]
    public void Connected_SecondConnection_ReturnsFalseAndUserRemainsOnline()
    {
        var tracker = new UserConnectionTracker();

        tracker.Connected("user-1", "connection-1");

        var becameOnlineAgain = tracker.Connected(
            "user-1",
            "connection-2");

        Assert.False(becameOnlineAgain);
        Assert.True(tracker.IsOnline("user-1"));
    }

    [Fact]
    public void Disconnected_OneOfMultipleConnections_DoesNotMarkUserOffline()
    {
        var tracker = new UserConnectionTracker();

        tracker.Connected("user-1", "connection-1");
        tracker.Connected("user-1", "connection-2");

        var becameOffline = tracker.Disconnected(
            "user-1",
            "connection-1");

        Assert.False(becameOffline);
        Assert.True(tracker.IsOnline("user-1"));
    }

    [Fact]
    public void Disconnected_LastConnection_MarksUserOffline()
    {
        var tracker = new UserConnectionTracker();

        tracker.Connected("user-1", "connection-1");

        var becameOffline = tracker.Disconnected(
            "user-1",
            "connection-1");

        Assert.True(becameOffline);
        Assert.False(tracker.IsOnline("user-1"));
    }
}
