using System.Text.Json;

namespace BotGlobal.Games.Domain.Autobus;

public sealed class AutobusSessionState
{
    public const int MaximumAnswerLength = 48;

    private AutobusSessionState()
    {
    }

    public AutobusSessionState(Guid sessionId, AutobusRuleset ruleset)
    {
        SessionId = sessionId;
        RoundCount = ruleset.RoundCount;
        RoundSeconds = ruleset.RoundSeconds;
        Difficulty = ruleset.Difficulty;
        CategoriesJson = JsonSerializer.Serialize(ruleset.Categories, AutobusJson.Options);
        UsedLettersJson = "[]";
        AnswersJson = "[]";
        ScoresJson = "[]";
        VotesJson = "[]";
        Phase = AutobusRoundPhase.Lobby;
    }

    public Guid SessionId { get; private set; }
    public int RoundCount { get; private set; }
    public int RoundSeconds { get; private set; }
    public string Difficulty { get; private set; } = null!;
    public string CategoriesJson { get; private set; } = null!;
    public string UsedLettersJson { get; private set; } = null!;
    public int CurrentRound { get; private set; }
    public string? CurrentLetter { get; private set; }
    public AutobusRoundPhase Phase { get; private set; }
    public DateTimeOffset? RoundStartedAtUtc { get; private set; }
    public DateTimeOffset? RoundDeadlineAtUtc { get; private set; }
    public DateTimeOffset? GraceEndsAtUtc { get; private set; }
    public DateTimeOffset? VoteDeadlineAtUtc { get; private set; }
    public string? RevealCategoryKey { get; private set; }
    public string AnswersJson { get; private set; } = null!;
    public string ScoresJson { get; private set; } = null!;
    public string VotesJson { get; private set; } = null!;
    public string? TieMessageCode { get; private set; }
    public long Version { get; private set; }
    public byte[] ConcurrencyToken { get; private set; } = [];

    public IReadOnlyList<AutobusCategory> Categories =>
        JsonSerializer.Deserialize<IReadOnlyList<AutobusCategory>>(CategoriesJson, AutobusJson.Options) ?? [];

    public IReadOnlyList<string> UsedLetters =>
        JsonSerializer.Deserialize<IReadOnlyList<string>>(UsedLettersJson, AutobusJson.Options) ?? [];

    public IReadOnlyList<AutobusAnswerRecord> Answers =>
        JsonSerializer.Deserialize<IReadOnlyList<AutobusAnswerRecord>>(AnswersJson, AutobusJson.Options) ?? [];

    public IReadOnlyList<AutobusScoreRecord> Scores =>
        JsonSerializer.Deserialize<IReadOnlyList<AutobusScoreRecord>>(ScoresJson, AutobusJson.Options) ?? [];

    public IReadOnlyList<AutobusVoteRecord> Votes =>
        JsonSerializer.Deserialize<IReadOnlyList<AutobusVoteRecord>>(VotesJson, AutobusJson.Options) ?? [];

    public AutobusRuleset ToRuleset(string key) =>
        new(key, RoundCount, RoundSeconds, Difficulty, Categories);

    public void StartFirstRound(AutobusRuleset ruleset, IReadOnlyCollection<Guid> playerIds, DateTimeOffset now)
    {
        if (Phase != AutobusRoundPhase.Lobby) return;
        WriteScores(playerIds.Select(x => new AutobusScoreRecord(x, 0)).ToArray());
        StartNextRound(ruleset, now);
    }

    public void Submit(
        Guid playerMembershipId,
        IReadOnlyDictionary<string, string> submittedAnswers,
        DateTimeOffset now)
    {
        if (Phase is not AutobusRoundPhase.Active and not AutobusRoundPhase.Grace)
        {
            throw new InvalidOperationException("The current Autobus round is not accepting answers.");
        }

        var submissionDeadline = Phase == AutobusRoundPhase.Grace
            ? GraceEndsAtUtc
            : RoundDeadlineAtUtc?.AddSeconds(5);
        if (submissionDeadline.HasValue && now >= submissionDeadline.Value)
        {
            throw new InvalidOperationException("The current Autobus round is already locked.");
        }

        var categoryKeys = Categories.Select(x => x.Key).ToHashSet(StringComparer.Ordinal);
        var answers = Answers
            .Where(x => x.PlayerMembershipId != playerMembershipId || x.Round != CurrentRound)
            .ToList();
        foreach (var category in categoryKeys)
        {
            var display = submittedAnswers.TryGetValue(category, out var value) ? value.Trim() : string.Empty;
            if (display.Length > MaximumAnswerLength)
            {
                throw new InvalidOperationException("An Autobus answer is too long.");
            }

            answers.Add(AutobusAnswerRecord.Pending(CurrentRound, playerMembershipId, category, display));
        }

        WriteAnswers(answers);
        AdvanceVersion();
    }

    public bool StartGrace(DateTimeOffset now)
    {
        if (Phase == AutobusRoundPhase.Reveal) return false;
        if (Phase == AutobusRoundPhase.Grace) return false;
        if (Phase != AutobusRoundPhase.Active)
        {
            throw new InvalidOperationException("The current Autobus round cannot start grace.");
        }

        var graceStartedAt = RoundDeadlineAtUtc.HasValue && now >= RoundDeadlineAtUtc.Value
            ? RoundDeadlineAtUtc.Value
            : now;
        GraceEndsAtUtc = graceStartedAt.AddSeconds(5);
        Phase = AutobusRoundPhase.Grace;
        AdvanceVersion();
        return true;
    }

    public void LockForReveal(
        IReadOnlyCollection<Guid> playerIds,
        AutobusAnswerValidator validator,
        DateTimeOffset now)
    {
        if (Phase == AutobusRoundPhase.Reveal) return;
        if (Phase == AutobusRoundPhase.Active && RoundDeadlineAtUtc.HasValue && now < RoundDeadlineAtUtc.Value)
        {
            throw new InvalidOperationException("The current Autobus round is still active.");
        }

        if (Phase == AutobusRoundPhase.Grace && GraceEndsAtUtc.HasValue && now < GraceEndsAtUtc.Value)
        {
            throw new InvalidOperationException("The Autobus grace countdown is still active.");
        }

        var categories = Categories.Select(x => x.Key).ToArray();
        var answers = Answers.ToList();
        foreach (var playerId in playerIds)
        {
            foreach (var category in categories)
            {
                if (answers.Any(x => x.Round == CurrentRound &&
                    x.PlayerMembershipId == playerId &&
                    x.CategoryKey == category))
                {
                    continue;
                }

                answers.Add(AutobusAnswerRecord.Pending(CurrentRound, playerId, category, string.Empty));
            }
        }

        answers = answers.Select(answer =>
        {
            if (answer.Round != CurrentRound) return answer;
            var verdict = validator.Validate(answer.CategoryKey, CurrentLetter ?? string.Empty, answer.DisplayAnswer);
            return answer.ApplyVerdict(verdict);
        }).ToList();

        answers = ScoreRound(answers, playerIds).ToList();
        WriteAnswers(answers);
        WriteScores(RebuildScores(answers, playerIds));
        RevealCategoryKey = categories.FirstOrDefault();
        VoteDeadlineAtUtc = CurrentRevealNeedsVote(answers) ? now.AddSeconds(AutobusRuleset.VoteSeconds) : null;
        Phase = AutobusRoundPhase.Reveal;
        TieMessageCode = null;
        AdvanceVersion();
    }

    public void Vote(
        Guid voterMembershipId,
        Guid answerOwnerMembershipId,
        string categoryKey,
        bool accept,
        IReadOnlyCollection<Guid> connectedPlayerIds,
        DateTimeOffset now)
    {
        if (Phase != AutobusRoundPhase.Reveal)
        {
            throw new InvalidOperationException("Votes are only accepted during reveal.");
        }

        if (!string.Equals(categoryKey, RevealCategoryKey, StringComparison.Ordinal))
        {
            throw new InvalidOperationException("Votes are only accepted for the revealed category.");
        }

        if (VoteDeadlineAtUtc.HasValue && now >= VoteDeadlineAtUtc.Value)
        {
            throw new InvalidOperationException("The Autobus vote is already closed.");
        }

        if (voterMembershipId == answerOwnerMembershipId)
        {
            throw new UnauthorizedAccessException("The answer owner cannot vote on their own answer.");
        }

        var answer = Answers.SingleOrDefault(x =>
            x.Round == CurrentRound &&
            x.PlayerMembershipId == answerOwnerMembershipId &&
            x.CategoryKey == categoryKey);
        if (answer is null || answer.Outcome != AutobusAnswerOutcome.NeedsVote)
        {
            throw new InvalidOperationException("The answer does not require a vote.");
        }

        if (!connectedPlayerIds.Contains(voterMembershipId))
        {
            throw new UnauthorizedAccessException("Only connected eligible players can vote.");
        }

        var votes = Votes
            .Where(x => !(x.Round == CurrentRound &&
                x.AnswerOwnerMembershipId == answerOwnerMembershipId &&
                x.CategoryKey == categoryKey &&
                x.VoterMembershipId == voterMembershipId))
            .ToList();
        votes.Add(new AutobusVoteRecord(CurrentRound, answerOwnerMembershipId, categoryKey, voterMembershipId, accept));
        WriteVotes(votes);
        ResolveVoteIfDecided(answerOwnerMembershipId, categoryKey, connectedPlayerIds);
        if (!CurrentRevealNeedsVote(Answers)) VoteDeadlineAtUtc = null;
        AdvanceVersion();
    }

    public bool AdvanceRevealOrRound(
        AutobusRuleset ruleset,
        IReadOnlyCollection<Guid> playerIds,
        IReadOnlyCollection<Guid> connectedPlayerIds,
        DateTimeOffset now)
    {
        if (Phase != AutobusRoundPhase.Reveal)
        {
            throw new InvalidOperationException("The current Autobus round is not revealing answers.");
        }

        ResolveExpiredVotes(connectedPlayerIds, now);
        if (CurrentRevealNeedsVote(Answers))
        {
            throw new InvalidOperationException("The current Autobus reveal still has answers awaiting votes.");
        }

        var categories = Categories.Select(x => x.Key).ToArray();
        var currentIndex = Array.IndexOf(categories, RevealCategoryKey);
        if (currentIndex >= 0 && currentIndex < categories.Length - 1)
        {
            RevealCategoryKey = categories[currentIndex + 1];
            VoteDeadlineAtUtc = CurrentRevealNeedsVote(Answers) ? now.AddSeconds(AutobusRuleset.VoteSeconds) : null;
            TieMessageCode = null;
            AdvanceVersion();
            return false;
        }

        if (CurrentRound >= RoundCount)
        {
            Phase = AutobusRoundPhase.Completed;
            RevealCategoryKey = null;
            VoteDeadlineAtUtc = null;
            AdvanceVersion();
            return true;
        }

        StartNextRound(ruleset, now);
        return false;
    }

    public void ResetForReplay(AutobusRuleset ruleset, IReadOnlyCollection<Guid> playerIds, DateTimeOffset now)
    {
        UsedLettersJson = "[]";
        CurrentRound = 0;
        CurrentLetter = null;
        AnswersJson = "[]";
        VotesJson = "[]";
        WriteScores(playerIds.Select(x => new AutobusScoreRecord(x, 0)).ToArray());
        Phase = AutobusRoundPhase.Lobby;
        VoteDeadlineAtUtc = null;
        StartNextRound(ruleset, now);
    }

    internal void AnonymizeMembership(Guid membershipId, Guid anonymousId)
    {
        WriteAnswers(Answers.Select(x => x.PlayerMembershipId == membershipId ? x with
        {
            PlayerMembershipId = anonymousId,
            DisplayAnswer = string.Empty,
            NormalizedAnswer = string.Empty,
            CanonicalValue = null
        } : x));
        WriteVotes(Votes.Select(x => x.AnswerOwnerMembershipId == membershipId || x.VoterMembershipId == membershipId ? x with
        {
            AnswerOwnerMembershipId = x.AnswerOwnerMembershipId == membershipId ? anonymousId : x.AnswerOwnerMembershipId,
            VoterMembershipId = x.VoterMembershipId == membershipId ? anonymousId : x.VoterMembershipId
        } : x));
        WriteScores(Scores.Select(x => x.PlayerMembershipId == membershipId ? x with { PlayerMembershipId = anonymousId } : x));
        AdvanceVersion();
    }

    private void StartNextRound(AutobusRuleset ruleset, DateTimeOffset now)
    {
        var used = UsedLetters.ToList();
        var letter = ruleset.PickNextLetter(used);
        used.Add(letter);
        UsedLettersJson = JsonSerializer.Serialize(used, AutobusJson.Options);
        CurrentRound++;
        CurrentLetter = letter;
        RoundStartedAtUtc = now;
        RoundDeadlineAtUtc = now.AddSeconds(RoundSeconds);
        GraceEndsAtUtc = null;
        RevealCategoryKey = null;
        VoteDeadlineAtUtc = null;
        TieMessageCode = null;
        Phase = AutobusRoundPhase.Active;
        AdvanceVersion();
    }

    private void ResolveVoteIfDecided(
        Guid answerOwnerMembershipId,
        string categoryKey,
        IReadOnlyCollection<Guid> connectedPlayerIds)
    {
        var eligible = connectedPlayerIds
            .Where(x => x != answerOwnerMembershipId)
            .Distinct()
            .ToArray();
        if (eligible.Length == 0) return;

        var votes = Votes
            .Where(x => x.Round == CurrentRound &&
                x.AnswerOwnerMembershipId == answerOwnerMembershipId &&
                x.CategoryKey == categoryKey &&
                eligible.Contains(x.VoterMembershipId))
            .DistinctBy(x => x.VoterMembershipId)
            .ToArray();

        var acceptCount = votes.Count(x => x.Accept);
        var rejectCount = votes.Length - acceptCount;
        var majority = eligible.Length / 2 + 1;
        if (acceptCount < majority && rejectCount < majority && votes.Length < eligible.Length) return;

        var accepted = acceptCount >= majority;
        if (!accepted && acceptCount == rejectCount) TieMessageCode = "autobus_vote_tie_rejected";

        var answers = Answers.Select(answer =>
            answer.Round == CurrentRound &&
            answer.PlayerMembershipId == answerOwnerMembershipId &&
            answer.CategoryKey == categoryKey
                ? answer.ApplyVote(accepted)
                : answer).ToList();
        answers = ScoreRound(answers, connectedPlayerIds.Append(answerOwnerMembershipId).Distinct().ToArray()).ToList();
        WriteAnswers(answers);
        WriteScores(RebuildScores(answers, Scores.Select(x => x.PlayerMembershipId).ToArray()));
    }

    private void ResolveExpiredVotes(IReadOnlyCollection<Guid> connectedPlayerIds, DateTimeOffset now)
    {
        if (!VoteDeadlineAtUtc.HasValue || now < VoteDeadlineAtUtc.Value) return;

        var currentCategory = RevealCategoryKey;
        var answers = Answers.Select(answer =>
            answer.Round == CurrentRound &&
            answer.CategoryKey == currentCategory &&
            answer.Outcome == AutobusAnswerOutcome.NeedsVote
                ? answer.RejectAfterVoteTimeout()
                : answer).ToList();
        answers = ScoreRound(answers, connectedPlayerIds).ToList();
        WriteAnswers(answers);
        WriteScores(RebuildScores(answers, Scores.Select(x => x.PlayerMembershipId).ToArray()));
        TieMessageCode = "autobus_vote_timeout_rejected";
        VoteDeadlineAtUtc = null;
    }

    private bool CurrentRevealNeedsVote(IReadOnlyList<AutobusAnswerRecord> answers) =>
        answers.Any(answer =>
            answer.Round == CurrentRound &&
            answer.CategoryKey == RevealCategoryKey &&
            answer.Outcome == AutobusAnswerOutcome.NeedsVote);

    private IReadOnlyList<AutobusAnswerRecord> ScoreRound(
        IReadOnlyList<AutobusAnswerRecord> answers,
        IReadOnlyCollection<Guid> playerIds)
    {
        var scored = answers.ToList();
        foreach (var category in Categories.Select(x => x.Key))
        {
            var accepted = scored
                .Where(x => x.Round == CurrentRound &&
                    x.CategoryKey == category &&
                    x.Outcome == AutobusAnswerOutcome.Accepted &&
                    !string.IsNullOrWhiteSpace(x.CanonicalValue))
                .GroupBy(x => x.CanonicalValue!, StringComparer.Ordinal)
                .ToDictionary(x => x.Key, x => x.Count(), StringComparer.Ordinal);

            scored = scored.Select(answer =>
            {
                if (answer.Round != CurrentRound || answer.CategoryKey != category) return answer;
                if (answer.Outcome != AutobusAnswerOutcome.Accepted || string.IsNullOrWhiteSpace(answer.CanonicalValue))
                {
                    return answer with { Score = 0, Scored = answer.Outcome != AutobusAnswerOutcome.NeedsVote, Duplicate = false };
                }

                var duplicate = accepted.TryGetValue(answer.CanonicalValue, out var count) && count > 1;
                return answer with { Score = duplicate ? 5 : 10, Scored = true, Duplicate = duplicate };
            }).ToList();
        }

        return scored;
    }

    private IReadOnlyList<AutobusScoreRecord> RebuildScores(
        IReadOnlyList<AutobusAnswerRecord> answers,
        IReadOnlyCollection<Guid> playerIds) =>
        playerIds
            .Distinct()
            .Select(playerId => new AutobusScoreRecord(
                playerId,
                answers.Where(x => x.PlayerMembershipId == playerId && x.Scored).Sum(x => x.Score)))
            .ToArray();

    private void WriteAnswers(IEnumerable<AutobusAnswerRecord> answers) =>
        AnswersJson = JsonSerializer.Serialize(answers.OrderBy(x => x.Round).ThenBy(x => x.CategoryKey).ThenBy(x => x.PlayerMembershipId), AutobusJson.Options);

    private void WriteScores(IEnumerable<AutobusScoreRecord> scores) =>
        ScoresJson = JsonSerializer.Serialize(scores.OrderByDescending(x => x.Score).ThenBy(x => x.PlayerMembershipId), AutobusJson.Options);

    private void WriteVotes(IEnumerable<AutobusVoteRecord> votes) =>
        VotesJson = JsonSerializer.Serialize(votes.OrderBy(x => x.Round).ThenBy(x => x.CategoryKey).ThenBy(x => x.AnswerOwnerMembershipId).ThenBy(x => x.VoterMembershipId), AutobusJson.Options);

    private void AdvanceVersion() => Version = checked(Version + 1);
}

public sealed record AutobusAnswerRecord(
    int Round,
    Guid PlayerMembershipId,
    string CategoryKey,
    string DisplayAnswer,
    string NormalizedAnswer,
    AutobusAnswerOutcome Outcome,
    string ReasonCode,
    string FriendlyMessageCode,
    string? CanonicalValue,
    int Score,
    bool Scored,
    bool Duplicate)
{
    public static AutobusAnswerRecord Pending(int round, Guid playerMembershipId, string categoryKey, string displayAnswer) =>
        new(
            round,
            playerMembershipId,
            categoryKey,
            displayAnswer.Trim(),
            ArabicTextNormalizer.Normalize(displayAnswer),
            AutobusAnswerOutcome.NeedsVote,
            "pending",
            "autobus_answer_pending",
            null,
            0,
            false,
            false);

    public AutobusAnswerRecord ApplyVerdict(AutobusValidationResult verdict) =>
        this with
        {
            Outcome = verdict.Outcome,
            ReasonCode = verdict.ReasonCode,
            FriendlyMessageCode = verdict.FriendlyMessageCode,
            CanonicalValue = verdict.CanonicalValue,
            Scored = verdict.Outcome == AutobusAnswerOutcome.Rejected,
            Score = 0,
            Duplicate = false
        };

    public AutobusAnswerRecord ApplyVote(bool accepted) =>
        this with
        {
            Outcome = accepted ? AutobusAnswerOutcome.Accepted : AutobusAnswerOutcome.Rejected,
            ReasonCode = accepted ? "vote_accepted" : "vote_rejected",
            FriendlyMessageCode = accepted ? "autobus_answer_vote_accepted" : "autobus_answer_vote_rejected",
            CanonicalValue = accepted ? CanonicalValue ?? NormalizedAnswer : CanonicalValue,
            Scored = !accepted,
            Score = 0,
            Duplicate = false
        };

    public AutobusAnswerRecord RejectAfterVoteTimeout() =>
        this with
        {
            Outcome = AutobusAnswerOutcome.Rejected,
            ReasonCode = "vote_timeout",
            FriendlyMessageCode = "autobus_answer_vote_timeout",
            Scored = true,
            Score = 0,
            Duplicate = false
        };
}

public sealed record AutobusScoreRecord(Guid PlayerMembershipId, int Score);

public sealed record AutobusVoteRecord(
    int Round,
    Guid AnswerOwnerMembershipId,
    string CategoryKey,
    Guid VoterMembershipId,
    bool Accept);

public sealed class AutobusCommand
{
    private AutobusCommand()
    {
    }

    public AutobusCommand(Guid id, Guid sessionId, string commandId, Guid playerMembershipId, string kind, long acceptedVersion, DateTimeOffset acceptedAtUtc)
    {
        Id = id;
        SessionId = sessionId;
        CommandId = commandId.Trim();
        PlayerMembershipId = playerMembershipId;
        Kind = kind.Trim();
        AcceptedVersion = acceptedVersion;
        AcceptedAtUtc = acceptedAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid SessionId { get; private set; }
    public string CommandId { get; private set; } = null!;
    public Guid PlayerMembershipId { get; private set; }
    public string Kind { get; private set; } = null!;
    public long AcceptedVersion { get; private set; }
    public DateTimeOffset AcceptedAtUtc { get; private set; }

    internal void Anonymize(Guid anonymousId)
    {
        PlayerMembershipId = anonymousId;
        CommandId = Guid.NewGuid().ToString("N");
    }
}
