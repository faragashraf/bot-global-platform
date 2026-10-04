using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BotGlobal.Games.Domain.Autobus;

public enum AutobusRoundPhase
{
    Lobby = 0,
    Active = 1,
    Grace = 2,
    Reveal = 3,
    Completed = 4
}

public enum AutobusAnswerOutcome
{
    Accepted = 0,
    Rejected = 1,
    NeedsVote = 2
}

public static class AutobusCategories
{
    public static readonly AutobusCategory BoyName = new("boy_name", "اسم ولد", "Boy name");
    public static readonly AutobusCategory GirlName = new("girl_name", "اسم بنت", "Girl name");
    public static readonly AutobusCategory Animal = new("animal", "حيوان", "Animal");
    public static readonly AutobusCategory Plant = new("plant", "نبات", "Plant");
    public static readonly AutobusCategory Object = new("object", "جماد", "Object");
    public static readonly AutobusCategory Place = new("country_city", "بلد / مدينة", "Country / city");
    public static readonly AutobusCategory Food = new("food", "أكلة", "Food");
    public static readonly AutobusCategory Profession = new("profession", "مهنة", "Profession");
    public static readonly AutobusCategory Cartoon = new("cartoon_character", "شخصية كرتون", "Cartoon character");
    public static readonly AutobusCategory HomeThing = new("thing_at_home", "شيء في البيت", "Thing at home");
    public static readonly AutobusCategory VisitPlace = new("place_to_visit", "مكان نزوره", "Place to visit");

    private static readonly AutobusCategory[] Defaults =
    [
        BoyName,
        GirlName,
        Animal,
        Plant,
        Object,
        Place
    ];

    private static readonly AutobusCategory[] All =
    [
        BoyName,
        GirlName,
        Animal,
        Plant,
        Object,
        Place,
        Food,
        Profession,
        Cartoon,
        HomeThing,
        VisitPlace
    ];

    public static IReadOnlyList<AutobusCategory> Resolve(IReadOnlyList<string>? requested)
    {
        if (requested is null || requested.Count == 0) return Defaults;

        var selected = requested
            .Select(key => All.SingleOrDefault(x => string.Equals(x.Key, key?.Trim(), StringComparison.OrdinalIgnoreCase)))
            .Where(x => x is not null)
            .Cast<AutobusCategory>()
            .DistinctBy(x => x.Key)
            .ToArray();
        return selected.Length == 0 ? Defaults : selected;
    }

    public static AutobusCategory Require(string key) =>
        All.SingleOrDefault(x => string.Equals(x.Key, key, StringComparison.OrdinalIgnoreCase))
        ?? throw new ArgumentException("Unknown Autobus category.", nameof(key));
}

public sealed record AutobusCategory(string Key, string ArabicName, string EnglishName);

public sealed record AutobusRuleset(
    string Key,
    int RoundCount,
    int RoundSeconds,
    string Difficulty,
    IReadOnlyList<AutobusCategory> Categories)
{
    public const int MinimumPlayers = 2;
    public const int MaximumPlayers = 8;
    public const int VoteSeconds = 15;

    public static AutobusRuleset FromRequest(
        string rulesetKey,
        int? roundCount,
        int? roundSeconds,
        string? difficulty,
        IReadOnlyList<string>? categories)
    {
        var rounds = roundCount is 5 or 10 ? roundCount.Value : KeyContains(rulesetKey, "10") ? 10 : 5;
        var seconds = roundSeconds is 60 or 90 ? roundSeconds.Value : KeyContains(rulesetKey, "90") ? 90 : 60;
        var normalizedDifficulty = NormalizeDifficulty(difficulty ?? DifficultyFromKey(rulesetKey));
        var selectedCategories = AutobusCategories.Resolve(categories);
        return new AutobusRuleset(
            string.IsNullOrWhiteSpace(rulesetKey) ? $"autobus-{rounds}x{seconds}-{normalizedDifficulty}" : rulesetKey.Trim(),
            rounds,
            seconds,
            normalizedDifficulty,
            selectedCategories);
    }

    public string PickNextLetter(IReadOnlyCollection<string> usedLetters)
    {
        var remaining = LettersForDifficulty(Difficulty)
            .Where(letter => !usedLetters.Contains(letter, StringComparer.Ordinal))
            .ToArray();
        if (remaining.Length == 0)
        {
            throw new InvalidOperationException("No unused Autobus letters remain.");
        }

        return remaining[0];
    }

    public static IReadOnlyList<string> LettersForDifficulty(string difficulty)
    {
        var easy = new[] { "أ", "ب", "ت", "ج", "ح", "د", "ر", "س", "م", "ن" };
        var medium = new[] { "ف", "ق", "ك", "ل", "ع", "ش", "ص", "ط", "أ", "ب", "ت", "ج", "ح", "د", "ر", "س", "م", "ن", "و", "ي" };
        var hard = new[] { "ث", "خ", "ذ", "ز", "ض", "ظ", "غ", "ص", "ط", "ش", "ع", "ق", "ف", "ك", "ل", "أ", "ب", "ت", "ج", "ح", "د", "ر", "س", "م", "ن", "و", "ي" };
        return NormalizeDifficulty(difficulty) switch
        {
            "easy" => easy,
            "hard" => hard,
            _ => medium
        };
    }

    private static bool KeyContains(string value, string token) =>
        value.Contains(token, StringComparison.OrdinalIgnoreCase);

    private static string DifficultyFromKey(string key) =>
        key.Contains("hard", StringComparison.OrdinalIgnoreCase) ? "hard" :
        key.Contains("easy", StringComparison.OrdinalIgnoreCase) ? "easy" :
        "medium";

    private static string NormalizeDifficulty(string value) =>
        value.Trim().ToLowerInvariant() switch
        {
            "easy" => "easy",
            "hard" => "hard",
            _ => "medium"
        };
}

public sealed record AutobusValidationResult(
    AutobusAnswerOutcome Outcome,
    string ReasonCode,
    string FriendlyMessageCode,
    string? CanonicalValue,
    double Confidence);

public sealed class AutobusAnswerValidator
{
    private readonly AutobusLexicon _lexicon = new();

    public AutobusValidationResult Validate(string categoryKey, string roundLetter, string displayAnswer)
    {
        var normalized = ArabicTextNormalizer.Normalize(displayAnswer);
        if (string.IsNullOrWhiteSpace(normalized))
        {
            return new(AutobusAnswerOutcome.Rejected, "empty", "autobus_answer_empty", null, 1);
        }

        if (!ArabicTextNormalizer.StartsWithLetter(normalized, roundLetter))
        {
            return new(AutobusAnswerOutcome.Rejected, "wrong_letter", "autobus_answer_wrong_letter", null, 1);
        }

        var match = _lexicon.Match(categoryKey, normalized);
        if (match.CanonicalValue is not null &&
            !ArabicTextNormalizer.StartsWithLetter(match.CanonicalValue, roundLetter))
        {
            return new(AutobusAnswerOutcome.Rejected, "wrong_letter", "autobus_answer_wrong_letter", null, 1);
        }

        return match.Kind switch
        {
            LexiconMatchKind.Exact => new(AutobusAnswerOutcome.Accepted, "lexicon_exact", "autobus_answer_accepted", match.CanonicalValue, 1),
            LexiconMatchKind.Alias => new(AutobusAnswerOutcome.Accepted, "lexicon_alias", "autobus_answer_accepted", match.CanonicalValue, .96),
            LexiconMatchKind.Typo => new(AutobusAnswerOutcome.Accepted, "lexicon_typo", "autobus_answer_typo_accepted", match.CanonicalValue, .86),
            _ => new(AutobusAnswerOutcome.NeedsVote, "unknown_word", "autobus_answer_needs_vote", normalized, .4)
        };
    }
}

public static class ArabicTextNormalizer
{
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);

    public static string Normalize(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;
        var decomposed = value.Trim().Normalize(NormalizationForm.FormD);
        var builder = new StringBuilder(decomposed.Length);
        foreach (var character in decomposed)
        {
            var category = CharUnicodeInfo.GetUnicodeCategory(character);
            if (category is UnicodeCategory.NonSpacingMark or UnicodeCategory.EnclosingMark) continue;
            if (character == '\u0640') continue; // tatweel
            builder.Append(character switch
            {
                'أ' or 'إ' or 'آ' => 'ا',
                'ى' => 'ي',
                _ => character
            });
        }

        return Whitespace.Replace(builder.ToString().Normalize(NormalizationForm.FormC), " ").Trim();
    }

    public static bool StartsWithLetter(string normalizedAnswer, string roundLetter)
    {
        var normalizedLetter = Normalize(roundLetter);
        if (normalizedAnswer.StartsWith(normalizedLetter, StringComparison.Ordinal)) return true;
        if (normalizedAnswer.StartsWith("ال", StringComparison.Ordinal) && normalizedAnswer.Length > 2)
        {
            return normalizedAnswer[2..].StartsWith(normalizedLetter, StringComparison.Ordinal);
        }

        return false;
    }
}

internal enum LexiconMatchKind
{
    None,
    Exact,
    Alias,
    Typo
}

internal sealed record LexiconMatch(LexiconMatchKind Kind, string? CanonicalValue);

internal sealed class AutobusLexicon
{
    private readonly Dictionary<string, List<LexiconEntry>> _entries;

    public AutobusLexicon()
    {
        _entries = Seed()
            .GroupBy(x => x.CategoryKey, StringComparer.Ordinal)
            .ToDictionary(x => x.Key, x => x.ToList(), StringComparer.Ordinal);
    }

    public LexiconMatch Match(string categoryKey, string normalized)
    {
        if (!_entries.TryGetValue(categoryKey, out var entries)) return new(LexiconMatchKind.None, null);

        foreach (var entry in entries)
        {
            if (entry.Canonical == normalized) return new(LexiconMatchKind.Exact, entry.Canonical);
            if (entry.Aliases.Contains(normalized, StringComparer.Ordinal)) return new(LexiconMatchKind.Alias, entry.Canonical);
        }

        var typo = entries
            .Where(x => x.Canonical.Length >= 4 && Math.Abs(x.Canonical.Length - normalized.Length) <= 1)
            .Select(x => new { Entry = x, Distance = EditDistance(x.Canonical, normalized) })
            .Where(x => x.Distance == 1)
            .OrderBy(x => x.Entry.Canonical.Length)
            .FirstOrDefault();
        return typo is null ? new(LexiconMatchKind.None, null) : new(LexiconMatchKind.Typo, typo.Entry.Canonical);
    }

    private static IEnumerable<LexiconEntry> Seed()
    {
        LexiconEntry E(AutobusCategory category, string canonical, params string[] aliases) =>
            new(category.Key, ArabicTextNormalizer.Normalize(canonical), aliases.Select(ArabicTextNormalizer.Normalize).ToArray());

        return
        [
            E(AutobusCategories.BoyName, "أحمد"), E(AutobusCategories.BoyName, "باسم"), E(AutobusCategories.BoyName, "تامر"),
            E(AutobusCategories.BoyName, "جمال"), E(AutobusCategories.BoyName, "حسن"), E(AutobusCategories.BoyName, "رامي"),
            E(AutobusCategories.BoyName, "سامي"), E(AutobusCategories.BoyName, "فادي"), E(AutobusCategories.BoyName, "كريم"),
            E(AutobusCategories.BoyName, "ليث"), E(AutobusCategories.BoyName, "يوسف"), E(AutobusCategories.BoyName, "عمر"),
            E(AutobusCategories.GirlName, "أمل"), E(AutobusCategories.GirlName, "بسمة"), E(AutobusCategories.GirlName, "تالا"),
            E(AutobusCategories.GirlName, "جميلة"), E(AutobusCategories.GirlName, "حنان"), E(AutobusCategories.GirlName, "ريم"),
            E(AutobusCategories.GirlName, "سارة"), E(AutobusCategories.GirlName, "فريدة"), E(AutobusCategories.GirlName, "ليلى"),
            E(AutobusCategories.GirlName, "ياسمين"), E(AutobusCategories.GirlName, "علا"),
            E(AutobusCategories.Animal, "أسد"), E(AutobusCategories.Animal, "بطة"), E(AutobusCategories.Animal, "تمساح"),
            E(AutobusCategories.Animal, "جمل"), E(AutobusCategories.Animal, "حصان"), E(AutobusCategories.Animal, "دب"),
            E(AutobusCategories.Animal, "سمكة"), E(AutobusCategories.Animal, "فيل"), E(AutobusCategories.Animal, "كلب"),
            E(AutobusCategories.Animal, "قطة"), E(AutobusCategories.Animal, "نمر"),
            E(AutobusCategories.Plant, "أرز"), E(AutobusCategories.Plant, "بصل"), E(AutobusCategories.Plant, "تفاح"),
            E(AutobusCategories.Plant, "جزر"), E(AutobusCategories.Plant, "ريحان"), E(AutobusCategories.Plant, "سمسم"),
            E(AutobusCategories.Plant, "فول"), E(AutobusCategories.Plant, "قمح"), E(AutobusCategories.Plant, "ليمون"),
            E(AutobusCategories.Plant, "نعناع"),
            E(AutobusCategories.Object, "إبرة"), E(AutobusCategories.Object, "باب"), E(AutobusCategories.Object, "تلفاز"),
            E(AutobusCategories.Object, "جوال"), E(AutobusCategories.Object, "حقيبة"), E(AutobusCategories.Object, "دفتر"),
            E(AutobusCategories.Object, "ساعة"), E(AutobusCategories.Object, "قلم"), E(AutobusCategories.Object, "كتاب"),
            E(AutobusCategories.Object, "مفتاح"),
            E(AutobusCategories.Place, "القاهرة", "قاهرة"), E(AutobusCategories.Place, "بيروت"), E(AutobusCategories.Place, "تونس"),
            E(AutobusCategories.Place, "جدة"), E(AutobusCategories.Place, "حلب"), E(AutobusCategories.Place, "دمشق"),
            E(AutobusCategories.Place, "الرياض", "رياض"), E(AutobusCategories.Place, "سوريا"), E(AutobusCategories.Place, "فلسطين"),
            E(AutobusCategories.Place, "قطر"), E(AutobusCategories.Place, "لبنان"), E(AutobusCategories.Place, "مصر"),
            E(AutobusCategories.Food, "أرز"), E(AutobusCategories.Food, "بامية"), E(AutobusCategories.Food, "تمر"),
            E(AutobusCategories.Food, "جبنة"), E(AutobusCategories.Food, "حمص"), E(AutobusCategories.Food, "سمبوسة"),
            E(AutobusCategories.Profession, "طبيب"), E(AutobusCategories.Profession, "معلم"), E(AutobusCategories.Profession, "نجار"),
            E(AutobusCategories.Cartoon, "علاء الدين", "الاء الدين"), E(AutobusCategories.Cartoon, "بكار"),
            E(AutobusCategories.HomeThing, "سرير"), E(AutobusCategories.HomeThing, "كرسي"), E(AutobusCategories.VisitPlace, "حديقة")
        ];
    }

    private static int EditDistance(string left, string right)
    {
        var costs = new int[right.Length + 1];
        for (var j = 0; j < costs.Length; j++) costs[j] = j;
        for (var i = 1; i <= left.Length; i++)
        {
            var previous = costs[0];
            costs[0] = i;
            for (var j = 1; j <= right.Length; j++)
            {
                var current = costs[j];
                costs[j] = left[i - 1] == right[j - 1]
                    ? previous
                    : Math.Min(Math.Min(costs[j] + 1, costs[j - 1] + 1), previous + 1);
                previous = current;
            }
        }

        return costs[^1];
    }

    private sealed record LexiconEntry(string CategoryKey, string Canonical, IReadOnlyList<string> Aliases);
}

internal static class AutobusJson
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);
}
