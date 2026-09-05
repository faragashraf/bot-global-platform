using System.Globalization;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.EntityFrameworkCore.Migrations;
using Microsoft.SqlServer.TransactSql.ScriptDom;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationOutboxSqlCompilationTests
{
    private const string Initial = "20260821164303_InitialNotificationCampaigns";
    private const string Outbox = "20260830111844_AddNotificationDeliveryOutbox";

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Generated_scripts_defer_backfill_compilation_until_columns_exist(bool idempotent)
    {
        var script = Script(idempotent);
        var staticUpdates = Nodes<UpdateStatement>(script);
        Assert.Empty(staticUpdates);
        Assert.DoesNotContain(Nodes<InsertStatement>(script), insert =>
            Table(insert.InsertSpecification.Target) == "NotificationDeliveryAttempts");

        var deferred = DeferredStatements(script);
        var key = Assert.Single(deferred, item =>
            Nodes<UpdateStatement>(item.Sql).Any(update =>
                Assignments(update).Any(set => Column(set.Column) == "DeliveryKey")));
        var history = Assert.Single(deferred, item =>
            Nodes<InsertStatement>(item.Sql).Any(insert =>
                Table(insert.InsertSpecification.Target) == "NotificationDeliveryAttempts"));

        var additions = Nodes<AlterTableAddTableElementStatement>(script)
            .SelectMany(add => add.Definition.ColumnDefinitions
                .Select(column => (Name: column.ColumnIdentifier.Value, Offset: add.StartOffset)))
            .ToDictionary(column => column.Name, column => column.Offset);
        Assert.True(additions["DeliveryKey"] < key.Offset);
        Assert.True(additions["CurrentAttemptId"] < history.Offset);
        Assert.True(key.Offset < history.Offset);
        Assert.True(Assert.Single(Nodes<CreateTableStatement>(script)).StartOffset < history.Offset);
    }

    [Fact]
    public void Filtered_index_predicate_is_compiled_after_current_attempt_column_exists()
    {
        var script = Script();
        Assert.DoesNotContain(Nodes<CreateIndexStatement>(script), index =>
            index.FilterPredicate is not null);
        var deferredIndex = Assert.Single(DeferredStatements(script)
            .SelectMany(item => Nodes<CreateIndexStatement>(item.Sql)));
        Assert.Equal("UX_NotificationRecipients_CurrentAttempt", deferredIndex.Name.Value);
        Assert.True(deferredIndex.Unique);
        Assert.Equal("CurrentAttemptId", Column(Assert.Single(deferredIndex.Columns).Column));
        var predicate = Assert.IsType<BooleanIsNullExpression>(deferredIndex.FilterPredicate);
        Assert.True(predicate.IsNot);
        Assert.Equal("CurrentAttemptId", Column(Assert.IsType<ColumnReferenceExpression>(predicate.Expression)));
    }

    [Theory]
    [InlineData("ABCDEF01-2345-6789-ABCD-EF0123456789", "01234567-89AB-CDEF-0123-456789ABCDEF", "FEDCBA98-7654-3210-FEDC-BA9876543210")]
    [InlineData("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "FFFFFFFF-EEEE-DDDD-CCCC-BBBBBBBBBBBB", "12345678-ABCD-EF01-2345-6789ABCDEF01")]
    public void Actual_backfill_expression_matches_runtime_delivery_key(string application, string campaign, string device)
    {
        var assignments = DeferredStatements(Script()).SelectMany(item => Nodes<UpdateStatement>(item.Sql))
            .SelectMany(Assignments);
        var keyExpression = Assert.Single(assignments, set => Column(set.Column) == "DeliveryKey").NewValue;
        var ids = new Dictionary<string, Guid>
        {
            ["PlatformClientId"] = Guid.Parse(application),
            ["CampaignId"] = Guid.Parse(campaign),
            ["MobileDeviceId"] = Guid.Parse(device)
        };

        // Interpret only the formatting expression from the parsed SQL; never connect to a database.
        // An unsupported expression fails rather than assuming compatibility with the runtime.
        var migrationKey = EvaluateKey(keyExpression, ids);
        Assert.Equal(NotificationRecipient.CreateDeliveryKey(ids["PlatformClientId"], ids["CampaignId"], ids["MobileDeviceId"]), migrationKey);
        Assert.Equal(98, migrationKey.Length);
    }

    public static TheoryData<int, int> HistoricalCases => new()
    {
        { 1, 0 }, { 7, 0 },
        { 2, 0 }, { 2, 1 }, { 2, 4 },
        { 3, 0 }, { 3, 1 }, { 3, 4 },
        { 4, 0 }, { 4, 1 }, { 4, 4 },
        { 5, 0 }, { 5, 1 }, { 5, 4 },
        { 6, 0 }, { 6, 1 }, { 6, 6 }
    };

    [Theory]
    [MemberData(nameof(HistoricalCases))]
    public void Parsed_historical_backfill_preserves_latest_known_attempt_contract(int status, int count)
    {
        var backfill = Assert.Single(DeferredStatements(Script()), item =>
            Nodes<InsertStatement>(item.Sql).Count != 0).Sql;
        var update = Assert.Single(Nodes<UpdateStatement>(backfill));
        var eligible = Assert.Single(Nodes<InPredicate>(update));
        var states = eligible.Values.Select(value => int.Parse(Assert.IsType<IntegerLiteral>(value).Value, CultureInfo.InvariantCulture)).ToArray();
        Assert.Equal([2, 3, 4, 5, 6], states);
        Assert.Equal(status is >= 2 and <= 6, states.Contains(status));
        var missingPointer = Assert.Single(Nodes<BooleanIsNullExpression>(update));
        Assert.False(missingPointer.IsNot);
        Assert.Equal("CurrentAttemptId", Column(Assert.IsType<ColumnReferenceExpression>(missingPointer.Expression)));
        Assert.Equal(BooleanBinaryExpressionType.And,
            Assert.IsType<BooleanBinaryExpression>(update.UpdateSpecification.WhereClause.SearchCondition).BinaryExpressionType);
        var newPointer = Assert.IsType<FunctionCall>(Assert.Single(Assignments(update),
            set => Column(set.Column) == "CurrentAttemptId").NewValue);
        Assert.Equal("NEWID", newPointer.FunctionName.Value);
        Assert.Empty(newPointer.Parameters);
        Assert.DoesNotContain(Assignments(update), set => Column(set.Column) == "Status");

        var normalization = Assert.IsType<SearchedCaseExpression>(Assert.Single(Assignments(update),
            set => Column(set.Column) == "AttemptCount").NewValue);
        var when = Assert.Single(normalization.WhenClauses);
        var comparison = Assert.IsType<BooleanComparisonExpression>(when.WhenExpression);
        Assert.Equal(BooleanComparisonType.LessThan, comparison.ComparisonType);
        Assert.Equal("AttemptCount", Column(Assert.IsType<ColumnReferenceExpression>(comparison.FirstExpression)));
        var threshold = int.Parse(Assert.IsType<IntegerLiteral>(comparison.SecondExpression).Value, CultureInfo.InvariantCulture);
        var minimum = int.Parse(Assert.IsType<IntegerLiteral>(when.ThenExpression).Value, CultureInfo.InvariantCulture);
        Assert.Equal("AttemptCount", Column(Assert.IsType<ColumnReferenceExpression>(normalization.ElseExpression)));
        Assert.Equal(Math.Max(1, count), count < threshold ? minimum : count);

        var insert = Assert.Single(Nodes<InsertStatement>(backfill));
        var select = Assert.IsType<QuerySpecification>(Assert.IsType<SelectInsertSource>(insert.InsertSpecification.InsertSource).Select);
        var values = insert.InsertSpecification.Columns.Select((column, index) =>
                (Name: Column(column), Value: Assert.IsType<SelectScalarExpression>(select.SelectElements[index]).Expression))
            .ToDictionary(item => item.Name, item => item.Value);
        var mapping = Assert.IsType<SimpleCaseExpression>(values["Status"]).WhenClauses
            .ToDictionary(clause => int.Parse(Assert.IsType<IntegerLiteral>(clause.WhenExpression).Value, CultureInfo.InvariantCulture),
                clause => int.Parse(Assert.IsType<IntegerLiteral>(clause.ThenExpression).Value, CultureInfo.InvariantCulture));
        if (states.Contains(status))
        {
            var expected = status switch { 2 => 5, 3 => 3, 4 => 4, 5 => 6, 6 => 7, _ => throw new InvalidOperationException() };
            Assert.Equal(expected, mapping[status]);
        }
        Assert.Equal("CurrentAttemptId", Column(Assert.IsType<ColumnReferenceExpression>(values["Id"])));
        Assert.Equal("AttemptCount", Column(Assert.IsType<ColumnReferenceExpression>(values["AttemptNumber"])));
        Assert.IsType<NullLiteral>(values["ProviderMessageId"]);
        Assert.Null(select.GroupByClause);
        Assert.Empty(Nodes<WhileStatement>(backfill));
    }

    [Fact]
    public void Migration_preserves_ef_transaction_ownership_and_records_history_last()
    {
        using var context = Context();
        var assembly = context.GetService<IMigrationsAssembly>();
        var migration = assembly.CreateMigration(assembly.Migrations[Outbox], context.Database.ProviderName!);
        var commands = context.GetService<IMigrationsSqlGenerator>().Generate(
            migration.UpOperations, context.GetService<IDesignTimeModel>().Model);
        Assert.All(commands, command => Assert.False(command.TransactionSuppressed));
        Assert.DoesNotContain(commands, command => Nodes<BeginTransactionStatement>(Parse(command.CommandText)).Count != 0);

        var script = Script();
        var begin = Assert.Single(Nodes<BeginTransactionStatement>(script));
        var commit = Assert.Single(Nodes<CommitTransactionStatement>(script));
        var abort = Assert.Single(Nodes<PredicateSetStatement>(script),
            set => set.Options.HasFlag(SetOptions.XactAbort));
        Assert.True(abort.IsOn);
        var history = Assert.Single(Nodes<InsertStatement>(script));
        Assert.Equal("__EFMigrationsHistory", Table(history.InsertSpecification.Target));
        Assert.True(begin.StartOffset < abort.StartOffset);
        Assert.True(abort.StartOffset < Assert.Single(Nodes<CreateTableStatement>(script)).StartOffset);
        Assert.True(history.StartOffset > Nodes<AlterTableStatement>(script).Max(statement => statement.StartOffset));
        Assert.True(history.StartOffset > Nodes<CreateIndexStatement>(script).Max(statement => statement.StartOffset));
        Assert.All(DeferredStatements(script), statement => Assert.True(statement.Offset < history.StartOffset));
        Assert.True(history.StartOffset < commit.StartOffset);
    }

    private static string EvaluateKey(ScalarExpression expression, IReadOnlyDictionary<string, Guid> ids) => expression switch
    {
        StringLiteral text => text.Value,
        BinaryExpression binary when binary.BinaryExpressionType == BinaryExpressionType.Add =>
            EvaluateKey(binary.FirstExpression, ids) + EvaluateKey(binary.SecondExpression, ids),
        ConvertCall conversion => ConvertGuid(conversion, ids),
        FunctionCall function when function.FunctionName.Value.Equals("LOWER", StringComparison.OrdinalIgnoreCase) =>
            EvaluateKey(Assert.Single(function.Parameters), ids).ToLowerInvariant(),
        FunctionCall function when function.FunctionName.Value.Equals("REPLACE", StringComparison.OrdinalIgnoreCase) =>
            EvaluateKey(function.Parameters[0], ids).Replace(EvaluateKey(function.Parameters[1], ids), EvaluateKey(function.Parameters[2], ids), StringComparison.Ordinal),
        _ => throw new InvalidOperationException($"Unreviewed delivery-key SQL expression: {expression.GetType().Name}")
    };

    private static string ConvertGuid(ConvertCall conversion, IReadOnlyDictionary<string, Guid> ids)
    {
        var type = Assert.IsType<SqlDataTypeReference>(conversion.DataType);
        Assert.Equal(SqlDataTypeOption.VarChar, type.SqlDataTypeOption);
        Assert.Equal("36", Assert.Single(type.Parameters).Value);
        // Use uppercase input to prove that the SQL LOWER call is material.
        return ids[Column(Assert.IsType<ColumnReferenceExpression>(conversion.Parameter))].ToString("D").ToUpperInvariant();
    }

    private static TSqlScript Script(bool idempotent = false)
    {
        using var context = Context();
        return Parse(context.GetService<IMigrator>().GenerateScript(Initial, Outbox,
            idempotent ? MigrationsSqlGenerationOptions.Idempotent : MigrationsSqlGenerationOptions.Default));
    }

    private static NotificationsDbContext Context() => new NotificationsDesignTimeDbContextFactory().CreateDbContext([]);

    private static TSqlScript Parse(string sql)
    {
        var script = new TSql160Parser(initialQuotedIdentifiers: true).Parse(new StringReader(sql), out var errors);
        Assert.Empty(errors);
        return Assert.IsType<TSqlScript>(script);
    }

    private static List<(int Offset, TSqlScript Sql)> DeferredStatements(TSqlFragment script) =>
        Nodes<ExecuteStatement>(script)
            .Where(statement => statement.ExecuteSpecification.ExecutableEntity is ExecutableStringList strings
                && strings.Strings.All(value => value is StringLiteral))
            .Select(statement => (statement.StartOffset, Parse(string.Concat(
                ((ExecutableStringList)statement.ExecuteSpecification.ExecutableEntity).Strings.Cast<StringLiteral>().Select(value => value.Value)))))
            .ToList();

    private static IEnumerable<AssignmentSetClause> Assignments(UpdateStatement update) =>
        update.UpdateSpecification.SetClauses.Cast<AssignmentSetClause>();

    private static string Column(ColumnReferenceExpression column) => column.MultiPartIdentifier.Identifiers[^1].Value;
    private static string Table(TableReference table) => Assert.IsType<NamedTableReference>(table).SchemaObject.BaseIdentifier.Value;

    private static List<T> Nodes<T>(TSqlFragment fragment) where T : TSqlFragment
    {
        var visitor = new Collector<T>();
        fragment.Accept(visitor);
        return visitor.Items;
    }

    private sealed class Collector<T> : TSqlFragmentVisitor where T : TSqlFragment
    {
        public List<T> Items { get; } = [];
        public override void Visit(TSqlFragment node)
        {
            if (node is T item) Items.Add(item);
        }
    }
}
