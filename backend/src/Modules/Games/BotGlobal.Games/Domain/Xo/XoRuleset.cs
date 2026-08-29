namespace BotGlobal.Games.Domain.Xo;

public sealed record XoRuleset
{
    public XoRuleset(
        string key,
        int boardSize,
        int winLength,
        int playerCount = 2,
        TimeSpan? turnTimeLimit = null,
        bool rematchEnabled = true,
        bool voiceEnabled = false,
        string? requiredEntitlement = null)
    {
        if (string.IsNullOrWhiteSpace(key))
        {
            throw new ArgumentException("Ruleset key is required.", nameof(key));
        }

        if (boardSize is < 3 or > 15)
        {
            throw new ArgumentOutOfRangeException(nameof(boardSize), "Board size must be between 3 and 15.");
        }

        if (winLength is < 3 || winLength > boardSize)
        {
            throw new ArgumentOutOfRangeException(nameof(winLength), "Win length must be between 3 and board size.");
        }

        if (playerCount != 2)
        {
            throw new ArgumentOutOfRangeException(nameof(playerCount), "XO currently supports exactly two players.");
        }

        Key = key.Trim();
        BoardSize = boardSize;
        WinLength = winLength;
        PlayerCount = playerCount;
        TurnTimeLimit = turnTimeLimit;
        RematchEnabled = rematchEnabled;
        VoiceEnabled = voiceEnabled;
        RequiredEntitlement = string.IsNullOrWhiteSpace(requiredEntitlement) ? null : requiredEntitlement.Trim();
    }

    public string Key { get; }
    public int BoardSize { get; }
    public int WinLength { get; }
    public int PlayerCount { get; }
    public TimeSpan? TurnTimeLimit { get; }
    public bool RematchEnabled { get; }
    public bool VoiceEnabled { get; }
    public string? RequiredEntitlement { get; }

    public static XoRuleset Classic => new("classic-3x3", 3, 3, voiceEnabled: true);
    public static XoRuleset Extended => new(
        "extended-5x5-win4",
        5,
        4,
        requiredEntitlement: "games.xo.extended");

    public static XoRuleset FromKey(string key) => key switch
    {
        "classic-3x3" => Classic,
        "extended-5x5-win4" => Extended,
        _ => throw new ArgumentException("Unknown XO ruleset.", nameof(key))
    };
}
