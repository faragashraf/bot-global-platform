-- Chat activation v003. Adds one-hour sender edit/delete tombstone metadata.
-- Review and record this file's SHA-256 outside the database before execution.
-- Session precondition: SET botglobal.chat_activation_database = '<approved database>';
-- Session precondition: SET botglobal.chat_activation_version = '003';
-- With psql use ON_ERROR_STOP=1. On any failure ROLLBACK/disconnect; never continue.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET LOCAL search_path = pg_catalog;

DO $guard$
DECLARE
    marker text;
BEGIN
    IF current_setting('server_version_num')::integer < 180000
       OR current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'chat_mutation_activation_requires_postgresql18_utf8';
    END IF;
    IF current_setting('botglobal.chat_activation_database', true) IS DISTINCT FROM current_database()
       OR current_setting('botglobal.chat_activation_version', true) IS DISTINCT FROM '003' THEN
        RAISE EXCEPTION 'chat_mutation_activation_target_or_version_not_approved';
    END IF;
    IF NOT pg_try_advisory_xact_lock(20261010, 183000) THEN
        RAISE EXCEPTION 'chat_mutation_activation_already_running';
    END IF;
    IF to_regclass('communication."ChatConversations"') IS NULL
       OR to_regclass('communication."ChatMessages"') IS NULL THEN
        RAISE EXCEPTION 'chat_mutation_activation_requires_chat_schema';
    END IF;
    SELECT obj_description('communication."ChatConversations"'::regclass) INTO marker;
    IF marker IS DISTINCT FROM 'botglobal-chat:002:20261010172000_AddChatMessageReplies:standalone-postgresql' THEN
        RAISE EXCEPTION 'chat_mutation_activation_unexpected_schema_marker';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'communication'
          AND table_name = 'ChatMessages'
          AND column_name IN ('EditedAtUtc', 'DeletedAtUtc')
    ) THEN
        RAISE EXCEPTION 'chat_mutation_activation_existing_columns_rejected';
    END IF;
END;
$guard$;

ALTER TABLE communication."ChatMessages"
    ADD COLUMN "EditedAtUtc" timestamp with time zone,
    ADD COLUMN "DeletedAtUtc" timestamp with time zone;

COMMENT ON TABLE communication."ChatConversations" IS 'botglobal-chat:003:20261010183000_AddChatMessageEditDelete:standalone-postgresql';
COMMIT;
