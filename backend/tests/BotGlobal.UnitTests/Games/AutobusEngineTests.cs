using BotGlobal.Games.Domain.Autobus;

namespace BotGlobal.UnitTests.Games;

public sealed class AutobusEngineTests
{
    private readonly Guid _first = Guid.NewGuid();
    private readonly Guid _second = Guid.NewGuid();
    private readonly Guid _third = Guid.NewGuid();

    [Theory]
    [InlineData("أحمد", "احمد")]
    [InlineData("آســد", "اسد")]
    [InlineData("يحيى", "يحيي")]
    public void Normalizes_arabic_for_comparison(string value, string expected)
    {
        Assert.Equal(expected, ArabicTextNormalizer.Normalize(value));
    }

    [Fact]
    public void Definite_article_alias_allows_conventional_place_names()
    {
        var validator = new AutobusAnswerValidator();

        var result = validator.Validate(AutobusCategories.Place.Key, "ر", "الرياض");

        Assert.Equal(AutobusAnswerOutcome.Accepted, result.Outcome);
        Assert.Equal("lexicon_exact", result.ReasonCode);
    }

    [Fact]
    public void Wrong_starting_letter_is_rejected_before_unknown_vote_path()
    {
        var validator = new AutobusAnswerValidator();

        var result = validator.Validate(AutobusCategories.Animal.Key, "ب", "أسد");

        Assert.Equal(AutobusAnswerOutcome.Rejected, result.Outcome);
        Assert.Equal("wrong_letter", result.ReasonCode);
    }

    [Fact]
    public void Unknown_word_with_correct_letter_needs_vote()
    {
        var validator = new AutobusAnswerValidator();

        var result = validator.Validate(AutobusCategories.Object.Key, "م", "مرجيحة");

        Assert.Equal(AutobusAnswerOutcome.NeedsVote, result.Outcome);
        Assert.Equal("unknown_word", result.ReasonCode);
    }

    [Fact]
    public void Conservative_typo_match_accepts_one_edit_only()
    {
        var validator = new AutobusAnswerValidator();

        Assert.Equal(
            AutobusAnswerOutcome.Accepted,
            validator.Validate(AutobusCategories.Object.Key, "ت", "تلفاذ").Outcome);
        Assert.Equal(
            AutobusAnswerOutcome.NeedsVote,
            validator.Validate(AutobusCategories.Object.Key, "ت", "تلفاذات").Outcome);
    }

    [Fact]
    public void Typo_correction_cannot_change_the_round_letter()
    {
        var result = new AutobusAnswerValidator().Validate(AutobusCategories.Object.Key, "س", "سلفاز");

        Assert.Equal(AutobusAnswerOutcome.Rejected, result.Outcome);
        Assert.Equal("wrong_letter", result.ReasonCode);
    }

    [Theory]
    [InlineData("boy_name", "أ", "أحمد")]
    [InlineData("girl_name", "ب", "بسمة")]
    [InlineData("animal", "ف", "فيل")]
    [InlineData("plant", "ق", "قمح")]
    [InlineData("object", "ك", "كتاب")]
    [InlineData("country_city", "ر", "الرياض")]
    [InlineData("food", "ت", "تمر")]
    [InlineData("profession", "ط", "طبيب")]
    [InlineData("cartoon_character", "ب", "بكار")]
    [InlineData("thing_at_home", "س", "سرير")]
    [InlineData("place_to_visit", "ح", "حديقة")]
    public void Every_supported_category_has_a_deterministic_accepted_example(
        string category,
        string letter,
        string answer)
    {
        var result = new AutobusAnswerValidator().Validate(category, letter, answer);

        Assert.Equal(AutobusAnswerOutcome.Accepted, result.Outcome);
    }

    [Fact]
    public void Difficulty_sets_start_with_distinct_representative_letters()
    {
        Assert.Equal("أ", AutobusRuleset.LettersForDifficulty("easy")[0]);
        Assert.Equal("ف", AutobusRuleset.LettersForDifficulty("medium")[0]);
        Assert.Equal("ث", AutobusRuleset.LettersForDifficulty("hard")[0]);
    }

    [Fact]
    public void Duplicate_accepted_answers_score_five_each()
    {
        var state = StartedState();

        state.Submit(_first, new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أحمد" }, Now());
        state.Submit(_second, new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أحمد" }, Now());
        state.LockForReveal([_first, _second], new AutobusAnswerValidator(), Now().AddSeconds(70));

        var scores = state.Answers.Where(x => x.CategoryKey == AutobusCategories.BoyName.Key).Select(x => x.Score).ToArray();
        Assert.Equal([5, 5], scores);
    }

    [Fact]
    public void Vote_acceptance_scores_unknown_answer()
    {
        var state = StartedState([_first, _second, _third], [AutobusCategories.Object]);
        state.Submit(_first, new Dictionary<string, string> { [AutobusCategories.Object.Key] = "أرجوحة" }, Now());
        state.LockForReveal([_first, _second, _third], new AutobusAnswerValidator(), Now().AddSeconds(70));

        state.Vote(_second, _first, AutobusCategories.Object.Key, accept: true, [_first, _second, _third], Now().AddSeconds(71));
        state.Vote(_third, _first, AutobusCategories.Object.Key, accept: true, [_first, _second, _third], Now().AddSeconds(72));

        var answer = state.Answers.Single(x => x.PlayerMembershipId == _first && x.CategoryKey == AutobusCategories.Object.Key);
        Assert.Equal(AutobusAnswerOutcome.Accepted, answer.Outcome);
        Assert.Equal(10, answer.Score);
    }

    [Fact]
    public void Tie_vote_rejects_by_default()
    {
        var state = StartedState([_first, _second, _third], [AutobusCategories.Object]);
        state.Submit(_first, new Dictionary<string, string> { [AutobusCategories.Object.Key] = "أرجوحة" }, Now());
        state.LockForReveal([_first, _second, _third], new AutobusAnswerValidator(), Now().AddSeconds(70));

        state.Vote(_second, _first, AutobusCategories.Object.Key, accept: true, [_first, _second, _third], Now().AddSeconds(71));
        state.Vote(_third, _first, AutobusCategories.Object.Key, accept: false, [_first, _second, _third], Now().AddSeconds(72));

        var answer = state.Answers.Single(x => x.PlayerMembershipId == _first && x.CategoryKey == AutobusCategories.Object.Key);
        Assert.Equal(AutobusAnswerOutcome.Rejected, answer.Outcome);
        Assert.Equal("autobus_vote_tie_rejected", state.TieMessageCode);
    }

    [Fact]
    public void Reveal_cannot_advance_until_vote_is_resolved_or_times_out()
    {
        var state = StartedState([_first, _second]);
        state.Submit(_first, new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أيمن" }, Now());
        var revealStartedAt = Now().AddSeconds(70);
        state.LockForReveal([_first, _second], new AutobusAnswerValidator(), revealStartedAt);

        Assert.NotNull(state.VoteDeadlineAtUtc);
        Assert.Throws<InvalidOperationException>(() => state.AdvanceRevealOrRound(
            state.ToRuleset("autobus-test"),
            [_first, _second],
            [_first, _second],
            revealStartedAt.AddSeconds(1)));

        Assert.False(state.AdvanceRevealOrRound(
            state.ToRuleset("autobus-test"),
            [_first, _second],
            [_first, _second],
            revealStartedAt.AddSeconds(AutobusRuleset.VoteSeconds + 1)));
        Assert.Equal(AutobusCategories.Object.Key, state.RevealCategoryKey);
        Assert.Equal(AutobusAnswerOutcome.Rejected, state.Answers.Single(x =>
            x.PlayerMembershipId == _first && x.CategoryKey == AutobusCategories.BoyName.Key).Outcome);
        Assert.Null(state.TieMessageCode);
    }

    [Fact]
    public void Vote_is_rejected_for_a_category_that_is_not_currently_revealed()
    {
        var state = StartedState([_first, _second]);
        state.Submit(_first, new Dictionary<string, string>
        {
            [AutobusCategories.BoyName.Key] = "أيمن",
            [AutobusCategories.Object.Key] = "أرجوحة"
        }, Now());
        state.LockForReveal([_first, _second], new AutobusAnswerValidator(), Now().AddSeconds(70));

        Assert.Equal(AutobusCategories.BoyName.Key, state.RevealCategoryKey);
        Assert.Throws<InvalidOperationException>(() => state.Vote(
            _second,
            _first,
            AutobusCategories.Object.Key,
            accept: true,
            [_first, _second],
            Now().AddSeconds(71)));
    }

    [Fact]
    public void Vote_is_rejected_at_the_authoritative_deadline()
    {
        var state = StartedState([_first, _second], [AutobusCategories.Object]);
        state.Submit(_first, new Dictionary<string, string> { [AutobusCategories.Object.Key] = "أرجوحة" }, Now());
        var revealStartedAt = Now().AddSeconds(70);
        state.LockForReveal([_first, _second], new AutobusAnswerValidator(), revealStartedAt);

        Assert.Throws<InvalidOperationException>(() => state.Vote(
            _second,
            _first,
            AutobusCategories.Object.Key,
            accept: true,
            [_first, _second],
            state.VoteDeadlineAtUtc!.Value));

        var answer = state.Answers.Single(x => x.PlayerMembershipId == _first);
        Assert.Equal(AutobusAnswerOutcome.NeedsVote, answer.Outcome);
        Assert.Equal(0, answer.Score);
    }

    [Fact]
    public void Submission_rejects_answers_longer_than_the_mobile_limit()
    {
        var state = StartedState();

        Assert.Throws<InvalidOperationException>(() => state.Submit(
            _first,
            new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = new('أ', 49) },
            Now()));
    }

    [Fact]
    public void Grace_accepts_answers_until_the_authoritative_grace_deadline()
    {
        var state = StartedState();
        var graceStarted = Now().AddSeconds(10);
        state.StartGrace(graceStarted);

        state.Submit(
            _first,
            new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أحمد" },
            graceStarted.AddSeconds(4.9));

        Assert.Equal("أحمد", state.Answers.Single(answer =>
            answer.PlayerMembershipId == _first && answer.CategoryKey == AutobusCategories.BoyName.Key).DisplayAnswer);
        Assert.Throws<InvalidOperationException>(() => state.Submit(
            _first,
            new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أيمن" },
            graceStarted.AddSeconds(5)));
    }

    [Fact]
    public void Delayed_grace_request_cannot_reopen_the_natural_submission_window()
    {
        var state = StartedState();
        var naturalDeadline = state.RoundDeadlineAtUtc!.Value;

        state.StartGrace(naturalDeadline.AddSeconds(2));

        Assert.Equal(naturalDeadline.AddSeconds(5), state.GraceEndsAtUtc);
        state.Submit(
            _first,
            new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أحمد" },
            naturalDeadline.AddSeconds(4.9));
        Assert.Throws<InvalidOperationException>(() => state.Submit(
            _first,
            new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أيمن" },
            naturalDeadline.AddSeconds(5)));
    }

    private AutobusSessionState StartedState(
        IReadOnlyCollection<Guid>? players = null,
        IReadOnlyList<AutobusCategory>? categories = null)
    {
        var ruleset = new AutobusRuleset(
            "autobus-test",
            5,
            60,
            "easy",
            categories ?? [AutobusCategories.BoyName, AutobusCategories.Object]);
        var state = new AutobusSessionState(Guid.NewGuid(), ruleset);
        state.StartFirstRound(ruleset, players ?? [_first, _second], Now());
        return state;
    }

    private static DateTimeOffset Now() => new(2026, 10, 4, 12, 0, 0, TimeSpan.Zero);
}
