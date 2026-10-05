using System.Data;
using System.Text;
using Microsoft.Data.SqlClient;
using Npgsql;
using NpgsqlTypes;

var execute = args.Contains("--execute", StringComparer.OrdinalIgnoreCase);
var skipConstraints = args.Contains("--skip-constraints", StringComparer.OrdinalIgnoreCase);

var sourceIdentity = RequireEnvironment("SOURCE_IDENTITY_CONNECTION");
var sourceShared = RequireEnvironment("SOURCE_SHARED_CONNECTION");
var targetHost = Environment.GetEnvironmentVariable("TARGET_POSTGRES_HOST") ?? "postgres";
var targetPort = Environment.GetEnvironmentVariable("TARGET_POSTGRES_PORT") ?? "5432";
var targetUser = RequireEnvironment("TARGET_POSTGRES_USER");
var targetPassword = RequireEnvironment("TARGET_POSTGRES_PASSWORD");

var targetAdmin = new NpgsqlConnectionStringBuilder
{
    Host = targetHost,
    Port = int.Parse(targetPort),
    Database = "postgres",
    Username = targetUser,
    Password = targetPassword,
    IncludeErrorDetail = false,
    Pooling = false
}.ConnectionString;

var plans = new[]
{
    new DatabasePlan(
        "Identity",
        sourceIdentity,
        "botglobal_identity",
        ["identity"]),
    new DatabasePlan(
        "Catalog",
        sourceShared,
        "botglobal_catalog",
        ["catalog"]),
    new DatabasePlan(
        "Communication",
        sourceShared,
        "botglobal_communication",
        ["calling", "communication"]),
    new DatabasePlan(
        "PlatformClients",
        sourceShared,
        "botglobal_platform_clients",
        ["platform_clients"]),
    new DatabasePlan(
        "Pairing",
        sourceShared,
        "botglobal_pairing",
        ["pairing"]),
    new DatabasePlan(
        "Notifications",
        sourceShared,
        "botglobal_notifications",
        ["notifications"]),
    new DatabasePlan(
        "Games",
        sourceShared,
        "botglobal_games",
        ["games"])
};

Console.WriteLine(execute
    ? "Mode: EXECUTE. Target PostgreSQL databases will be reset."
    : "Mode: DRY RUN. No target changes will be made.");

foreach (var plan in plans)
{
    await using var source = new SqlConnection(NormalizeSqlServerConnection(plan.SourceConnectionString));
    await source.OpenAsync();

    var sourceTables = await ReadSourceTablesAsync(source, plan.Schemas);
    var totalRows = sourceTables.Sum(table => table.RowCount);
    Console.WriteLine($"{plan.Name}: {sourceTables.Count} tables, {totalRows} source rows.");

    if (!execute)
    {
        foreach (var table in sourceTables.OrderBy(table => table.Schema).ThenBy(table => table.Name))
        {
            Console.WriteLine($"  {table.Schema}.{table.Name}: {table.RowCount}");
        }

        continue;
    }

    await ResetTargetDatabaseAsync(targetAdmin, plan.TargetDatabase);

    var targetConnection = new NpgsqlConnectionStringBuilder
    {
        Host = targetHost,
        Port = int.Parse(targetPort),
        Database = plan.TargetDatabase,
        Username = targetUser,
        Password = targetPassword,
        IncludeErrorDetail = false,
        Pooling = false
    }.ConnectionString;

    await using var target = new NpgsqlConnection(targetConnection);
    await target.OpenAsync();

    foreach (var schema in plan.Schemas)
    {
        await ExecutePostgresAsync(target, $"create schema if not exists {PgIdent(schema)};");
    }

    foreach (var table in sourceTables)
    {
        await CreateTableAsync(target, table);
    }

    foreach (var table in sourceTables)
    {
        var copied = await CopyTableAsync(source, target, table);
        Console.WriteLine($"  copied {table.Schema}.{table.Name}: {copied}");
    }

    foreach (var table in sourceTables)
    {
        await ResetIdentitySequencesAsync(target, table);
    }

    if (!skipConstraints)
    {
        await AddPrimaryKeysAsync(source, target, sourceTables);
        await AddIndexesAsync(source, target, sourceTables);
        await AddForeignKeysAsync(source, target, sourceTables);
    }

    Console.WriteLine($"{plan.Name}: migrated to {plan.TargetDatabase}.");
}

return 0;

static string RequireEnvironment(string name) =>
    Environment.GetEnvironmentVariable(name)
    ?? throw new InvalidOperationException($"Environment variable '{name}' is required.");

static string NormalizeSqlServerConnection(string connectionString)
{
    var builder = new SqlConnectionStringBuilder(connectionString)
    {
        TrustServerCertificate = true,
        ConnectTimeout = 30,
        ApplicationIntent = ApplicationIntent.ReadOnly
    };

    return builder.ConnectionString;
}

static async Task ResetTargetDatabaseAsync(string adminConnectionString, string database)
{
    await using var connection = new NpgsqlConnection(adminConnectionString);
    await connection.OpenAsync();

    await ExecutePostgresAsync(
        connection,
        $"""
        drop database if exists {PgIdent(database)} with (force);
        create database {PgIdent(database)};
        """);
}

static async Task<List<SourceTable>> ReadSourceTablesAsync(
    SqlConnection connection,
    IReadOnlyCollection<string> schemas)
{
    var schemaParameters = schemas
        .Select((_, index) => $"@schema{index}")
        .ToArray();

    await using var command = connection.CreateCommand();
    command.CommandText =
        $"""
        select
            s.name as SchemaName,
            t.name as TableName,
            sum(p.rows) as SourceRowCount
        from sys.tables t
        join sys.schemas s on s.schema_id = t.schema_id
        join sys.partitions p on p.object_id = t.object_id and p.index_id in (0, 1)
        where s.name in ({string.Join(", ", schemaParameters)})
        group by s.name, t.name
        order by s.name, t.name;
        """;

    for (var i = 0; i < schemas.Count; i++)
    {
        command.Parameters.AddWithValue(schemaParameters[i], schemas.ElementAt(i));
    }

    var tables = new List<SourceTable>();
    await using var reader = await command.ExecuteReaderAsync();
    while (await reader.ReadAsync())
    {
        tables.Add(new SourceTable(
            reader.GetString(0),
            reader.GetString(1),
            Convert.ToInt64(reader.GetValue(2)),
            []));
    }

    await reader.CloseAsync();

    foreach (var table in tables)
    {
        table.Columns.AddRange(await ReadSourceColumnsAsync(connection, table.Schema, table.Name));
    }

    return tables;
}

static async Task<List<SourceColumn>> ReadSourceColumnsAsync(
    SqlConnection connection,
    string schema,
    string table)
{
    await using var command = connection.CreateCommand();
    command.CommandText =
        """
        select
            c.name,
            typ.name,
            c.max_length,
            c.precision,
            c.scale,
            c.is_nullable,
            cast(columnproperty(c.object_id, c.name, 'IsIdentity') as bit) as IsIdentity,
            ic.seed_value,
            ic.increment_value
        from sys.columns c
        join sys.types typ on typ.user_type_id = c.user_type_id
        join sys.tables t on t.object_id = c.object_id
        join sys.schemas s on s.schema_id = t.schema_id
        left join sys.identity_columns ic on ic.object_id = c.object_id and ic.column_id = c.column_id
        where s.name = @schema and t.name = @table
        order by c.column_id;
        """;
    command.Parameters.AddWithValue("@schema", schema);
    command.Parameters.AddWithValue("@table", table);

    var columns = new List<SourceColumn>();
    await using var reader = await command.ExecuteReaderAsync();
    while (await reader.ReadAsync())
    {
        columns.Add(new SourceColumn(
            reader.GetString(0),
            reader.GetString(1),
            Convert.ToInt32(reader.GetValue(2)),
            Convert.ToInt32(reader.GetValue(3)),
            Convert.ToInt32(reader.GetValue(4)),
            reader.GetBoolean(5),
            reader.GetBoolean(6)));
    }

    return columns;
}

static async Task CreateTableAsync(NpgsqlConnection target, SourceTable table)
{
    var columns = table.Columns.Select(column =>
    {
        var type = ToPostgresType(column);
        var identity = column.IsIdentity ? " generated by default as identity" : "";
        var nullable = column.IsNullable ? "" : " not null";
        return $"{PgIdent(column.Name)} {type}{identity}{nullable}";
    });

    await ExecutePostgresAsync(
        target,
        $"create table {PgIdent(table.Schema)}.{PgIdent(table.Name)} ({string.Join(", ", columns)});");
}

static string ToPostgresType(SourceColumn column)
{
    var type = column.SqlType.ToLowerInvariant();
    return type switch
    {
        "uniqueidentifier" => "uuid",
        "nvarchar" or "varchar" or "nchar" or "char" => column.MaxLength < 0
            ? "text"
            : $"character varying({(type is "nvarchar" or "nchar" ? column.MaxLength / 2 : column.MaxLength)})",
        "text" or "ntext" => "text",
        "bit" => "boolean",
        "tinyint" => "smallint",
        "smallint" => "smallint",
        "int" => "integer",
        "bigint" => "bigint",
        "decimal" or "numeric" => $"numeric({column.Precision},{column.Scale})",
        "float" => "double precision",
        "real" => "real",
        "datetimeoffset" => "timestamp with time zone",
        "datetime2" or "datetime" or "smalldatetime" => "timestamp with time zone",
        "date" => "date",
        "time" => "time",
        "varbinary" or "binary" or "image" or "timestamp" or "rowversion" => "bytea",
        "xml" => "xml",
        _ => throw new NotSupportedException(
            $"Unsupported SQL Server type '{column.SqlType}' for {column.Name}.")
    };
}

static async Task<long> CopyTableAsync(
    SqlConnection source,
    NpgsqlConnection target,
    SourceTable table)
{
    if (table.Columns.Count == 0)
    {
        return 0;
    }

    await using var read = source.CreateCommand();
    var sourceColumns = string.Join(", ", table.Columns.Select(column => SqlIdent(column.Name)));
    read.CommandText =
        $"select {sourceColumns} from {SqlIdent(table.Schema)}.{SqlIdent(table.Name)};";
    read.CommandTimeout = 0;

    await using var reader = await read.ExecuteReaderAsync(CommandBehavior.SequentialAccess);

    var targetColumns = string.Join(", ", table.Columns.Select(column => PgIdent(column.Name)));
    var parameters = string.Join(", ", table.Columns.Select((_, index) => $"@p{index}"));
    var insertSql =
        $"insert into {PgIdent(table.Schema)}.{PgIdent(table.Name)} ({targetColumns}) values ({parameters});";

    var count = 0L;
    await using var transaction = await target.BeginTransactionAsync();
    while (await reader.ReadAsync())
    {
        await using var insert = new NpgsqlCommand(insertSql, target, transaction);
        for (var i = 0; i < table.Columns.Count; i++)
        {
            var value = await reader.IsDBNullAsync(i) ? DBNull.Value : reader.GetValue(i);
            insert.Parameters.AddWithValue($"p{i}", ConvertForPostgres(value));
        }

        await insert.ExecuteNonQueryAsync();
        count++;
    }

    await transaction.CommitAsync();
    return count;
}

static object ConvertForPostgres(object value) =>
    value switch
    {
        DateTime dateTime => DateTime.SpecifyKind(dateTime, DateTimeKind.Utc),
        DateTimeOffset dateTimeOffset => dateTimeOffset.ToUniversalTime(),
        _ => value
    };

static async Task ResetIdentitySequencesAsync(NpgsqlConnection target, SourceTable table)
{
    var relationName = $"{PgIdent(table.Schema)}.{PgIdent(table.Name)}";
    foreach (var column in table.Columns.Where(column => column.IsIdentity))
    {
        await ExecutePostgresAsync(
            target,
            $"""
            select setval(
              pg_get_serial_sequence('{PgString(relationName)}', '{PgString(column.Name)}'),
              coalesce((select max({PgIdent(column.Name)}) from {PgIdent(table.Schema)}.{PgIdent(table.Name)}), 1),
              (select count(*) > 0 from {PgIdent(table.Schema)}.{PgIdent(table.Name)})
            );
            """);
    }
}

static async Task AddPrimaryKeysAsync(
    SqlConnection source,
    NpgsqlConnection target,
    IReadOnlyCollection<SourceTable> tables)
{
    var tableSet = tables.Select(table => $"{table.Schema}.{table.Name}").ToHashSet(StringComparer.OrdinalIgnoreCase);
    await using var command = source.CreateCommand();
    command.CommandText =
        """
        select
            s.name as SchemaName,
            t.name as TableName,
            kc.name as ConstraintName,
            c.name as ColumnName,
            ic.key_ordinal
        from sys.key_constraints kc
        join sys.tables t on t.object_id = kc.parent_object_id
        join sys.schemas s on s.schema_id = t.schema_id
        join sys.index_columns ic on ic.object_id = t.object_id and ic.index_id = kc.unique_index_id
        join sys.columns c on c.object_id = t.object_id and c.column_id = ic.column_id
        where kc.type = 'PK'
        order by s.name, t.name, kc.name, ic.key_ordinal;
        """;

    var groups = new Dictionary<(string Schema, string Table, string Name), List<string>>();
    await using var reader = await command.ExecuteReaderAsync();
    while (await reader.ReadAsync())
    {
        var schema = reader.GetString(0);
        var table = reader.GetString(1);
        if (!tableSet.Contains($"{schema}.{table}"))
        {
            continue;
        }

        var key = (schema, table, reader.GetString(2));
        if (!groups.TryGetValue(key, out var columns))
        {
            columns = [];
            groups[key] = columns;
        }

        columns.Add(reader.GetString(3));
    }

    foreach (var ((schema, table, name), columns) in groups)
    {
        await ExecutePostgresAsync(
            target,
            $"alter table {PgIdent(schema)}.{PgIdent(table)} add constraint {PgIdent(name)} primary key ({string.Join(", ", columns.Select(PgIdent))});");
    }
}

static async Task AddIndexesAsync(
    SqlConnection source,
    NpgsqlConnection target,
    IReadOnlyCollection<SourceTable> tables)
{
    var tableSet = tables.Select(table => $"{table.Schema}.{table.Name}").ToHashSet(StringComparer.OrdinalIgnoreCase);
    await using var command = source.CreateCommand();
    command.CommandText =
        """
        select
            s.name as SchemaName,
            t.name as TableName,
            i.name as IndexName,
            i.is_unique,
            i.has_filter,
            i.filter_definition,
            c.name as ColumnName,
            ic.key_ordinal
        from sys.indexes i
        join sys.tables t on t.object_id = i.object_id
        join sys.schemas s on s.schema_id = t.schema_id
        join sys.index_columns ic on ic.object_id = i.object_id and ic.index_id = i.index_id
        join sys.columns c on c.object_id = i.object_id and c.column_id = ic.column_id
        where i.is_primary_key = 0
          and i.name is not null
          and ic.is_included_column = 0
        order by s.name, t.name, i.name, ic.key_ordinal;
        """;

    var groups = new Dictionary<(string Schema, string Table, string Name, bool Unique, string? Filter), List<string>>();
    await using var reader = await command.ExecuteReaderAsync();
    while (await reader.ReadAsync())
    {
        var schema = reader.GetString(0);
        var table = reader.GetString(1);
        if (!tableSet.Contains($"{schema}.{table}"))
        {
            continue;
        }

        var filter = reader.GetBoolean(4) ? reader.GetString(5) : null;
        var key = (schema, table, reader.GetString(2), reader.GetBoolean(3), filter);
        if (!groups.TryGetValue(key, out var columns))
        {
            columns = [];
            groups[key] = columns;
        }

        columns.Add(reader.GetString(6));
    }

    foreach (var ((schema, table, name, unique, filter), columns) in groups)
    {
        var translatedFilter = string.IsNullOrWhiteSpace(filter)
            ? ""
            : $" where {TranslateSqlServerPredicate(filter)}";
        var uniqueSql = unique ? "unique " : "";
        await ExecutePostgresAsync(
            target,
            $"create {uniqueSql}index {PgIdent(name)} on {PgIdent(schema)}.{PgIdent(table)} ({string.Join(", ", columns.Select(PgIdent))}){translatedFilter};");
    }
}

static async Task AddForeignKeysAsync(
    SqlConnection source,
    NpgsqlConnection target,
    IReadOnlyCollection<SourceTable> tables)
{
    var tableSet = tables.Select(table => $"{table.Schema}.{table.Name}").ToHashSet(StringComparer.OrdinalIgnoreCase);
    await using var command = source.CreateCommand();
    command.CommandText =
        """
        select
            ps.name as ParentSchema,
            pt.name as ParentTable,
            fk.name as ForeignKeyName,
            pc.name as ParentColumn,
            rs.name as ReferencedSchema,
            rt.name as ReferencedTable,
            rc.name as ReferencedColumn,
            fkc.constraint_column_id,
            fk.delete_referential_action_desc
        from sys.foreign_keys fk
        join sys.tables pt on pt.object_id = fk.parent_object_id
        join sys.schemas ps on ps.schema_id = pt.schema_id
        join sys.tables rt on rt.object_id = fk.referenced_object_id
        join sys.schemas rs on rs.schema_id = rt.schema_id
        join sys.foreign_key_columns fkc on fkc.constraint_object_id = fk.object_id
        join sys.columns pc on pc.object_id = pt.object_id and pc.column_id = fkc.parent_column_id
        join sys.columns rc on rc.object_id = rt.object_id and rc.column_id = fkc.referenced_column_id
        order by ps.name, pt.name, fk.name, fkc.constraint_column_id;
        """;

    var groups = new Dictionary<ForeignKeyKey, ForeignKeyColumns>();
    await using var reader = await command.ExecuteReaderAsync();
    while (await reader.ReadAsync())
    {
        var parentSchema = reader.GetString(0);
        var parentTable = reader.GetString(1);
        var referencedSchema = reader.GetString(4);
        var referencedTable = reader.GetString(5);
        if (!tableSet.Contains($"{parentSchema}.{parentTable}")
            || !tableSet.Contains($"{referencedSchema}.{referencedTable}"))
        {
            continue;
        }

        var key = new ForeignKeyKey(
            parentSchema,
            parentTable,
            reader.GetString(2),
            referencedSchema,
            referencedTable,
            reader.GetString(8));
        if (!groups.TryGetValue(key, out var columns))
        {
            columns = new ForeignKeyColumns();
            groups[key] = columns;
        }

        columns.Parent.Add(reader.GetString(3));
        columns.Referenced.Add(reader.GetString(6));
    }

    foreach (var (key, columns) in groups)
    {
        var onDelete = key.DeleteAction switch
        {
            "CASCADE" => " on delete cascade",
            "SET_NULL" => " on delete set null",
            _ => ""
        };
        await ExecutePostgresAsync(
            target,
            $"""
            alter table {PgIdent(key.ParentSchema)}.{PgIdent(key.ParentTable)}
            add constraint {PgIdent(key.Name)}
            foreign key ({string.Join(", ", columns.Parent.Select(PgIdent))})
            references {PgIdent(key.ReferencedSchema)}.{PgIdent(key.ReferencedTable)}
            ({string.Join(", ", columns.Referenced.Select(PgIdent))}){onDelete};
            """);
    }
}

static string TranslateSqlServerPredicate(string predicate)
{
    var builder = new StringBuilder(predicate);
    builder.Replace("[", "\"");
    builder.Replace("]", "\"");
    builder.Replace("N'", "'");
    return builder.ToString();
}

static async Task ExecutePostgresAsync(NpgsqlConnection connection, string sql)
{
    await using var command = new NpgsqlCommand(sql, connection);
    await command.ExecuteNonQueryAsync();
}

static string SqlIdent(string value) => $"[{value.Replace("]", "]]")}]";
static string PgIdent(string value) => $"\"{value.Replace("\"", "\"\"")}\"";
static string PgString(string value) => value.Replace("'", "''");

internal sealed record DatabasePlan(
    string Name,
    string SourceConnectionString,
    string TargetDatabase,
    string[] Schemas);

internal sealed record SourceTable(
    string Schema,
    string Name,
    long RowCount,
    List<SourceColumn> Columns);

internal sealed record SourceColumn(
    string Name,
    string SqlType,
    int MaxLength,
    int Precision,
    int Scale,
    bool IsNullable,
    bool IsIdentity);

internal sealed record ForeignKeyKey(
    string ParentSchema,
    string ParentTable,
    string Name,
    string ReferencedSchema,
    string ReferencedTable,
    string DeleteAction);

internal sealed class ForeignKeyColumns
{
    public List<string> Parent { get; } = [];
    public List<string> Referenced { get; } = [];
}
