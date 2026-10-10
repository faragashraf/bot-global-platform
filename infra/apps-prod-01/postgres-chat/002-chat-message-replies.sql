-- Chat activation v002. Adds message reply preview columns to the standalone Chat schema.
-- Review and record this file's SHA-256 outside the database before execution.
-- Session precondition: SET botglobal.chat_activation_database = '<approved database>';
-- Session precondition: SET botglobal.chat_activation_version = '002';
-- With psql use ON_ERROR_STOP=1. On any failure ROLLBACK/disconnect; never continue.
-- History: the ChatConversations comment is this standalone version's marker. No EF
-- history is inserted, and no claim is made about earlier SQL Server migrations.
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
        RAISE EXCEPTION 'chat_reply_activation_requires_postgresql18_utf8';
    END IF;
    IF current_setting('botglobal.chat_activation_database', true) IS DISTINCT FROM current_database()
       OR current_setting('botglobal.chat_activation_version', true) IS DISTINCT FROM '002' THEN
        RAISE EXCEPTION 'chat_reply_activation_target_or_version_not_approved';
    END IF;
    IF NOT pg_try_advisory_xact_lock(20261010, 172000) THEN
        RAISE EXCEPTION 'chat_reply_activation_already_running';
    END IF;
    IF to_regclass('communication."ChatConversations"') IS NULL
       OR to_regclass('communication."ChatMessages"') IS NULL THEN
        RAISE EXCEPTION 'chat_reply_activation_requires_v001_schema';
    END IF;
    SELECT obj_description('communication."ChatConversations"'::regclass) INTO marker;
    IF marker IS DISTINCT FROM 'botglobal-chat:001:20261007153116_AddApplicationScopedChat:standalone-postgresql' THEN
        RAISE EXCEPTION 'chat_reply_activation_unexpected_schema_marker';
    END IF;
    IF to_regclass('communication."__EFMigrationsHistory"') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM communication."__EFMigrationsHistory"
                   WHERE "MigrationId" IN ('20261007153116_AddApplicationScopedChat', '20261010172000_AddChatMessageReplies')) THEN
            RAISE EXCEPTION 'chat_reply_activation_existing_ef_history_rejected';
        END IF;
    END IF;
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'communication'
          AND table_name = 'ChatMessages'
          AND column_name IN (
              'ReplyToMessageId',
              'ReplyToSenderSubjectId',
              'ReplyToKind',
              'ReplyToText',
              'ReplyToVoiceDurationMilliseconds'
          )
    ) THEN
        RAISE EXCEPTION 'chat_reply_activation_existing_columns_rejected';
    END IF;
    IF to_regclass('communication."IX_ChatMessages_ApplicationId_ReplyToMessageId"') IS NOT NULL THEN
        RAISE EXCEPTION 'chat_reply_activation_existing_index_rejected';
    END IF;
END;
$guard$;

ALTER TABLE communication."ChatMessages"
    ADD COLUMN "ReplyToMessageId" uuid,
    ADD COLUMN "ReplyToSenderSubjectId" character varying(200) COLLATE pg_catalog."C",
    ADD COLUMN "ReplyToKind" integer,
    ADD COLUMN "ReplyToText" character varying(240),
    ADD COLUMN "ReplyToVoiceDurationMilliseconds" integer;

CREATE INDEX "IX_ChatMessages_ApplicationId_ReplyToMessageId"
    ON communication."ChatMessages" ("ApplicationId", "ReplyToMessageId");

ALTER TABLE communication."ChatMessages"
    ADD CONSTRAINT "FK_ChatMessages_ChatMessages_ApplicationId_ReplyToMessageId"
    FOREIGN KEY ("ApplicationId", "ReplyToMessageId")
    REFERENCES communication."ChatMessages" ("ApplicationId", "Id")
    ON DELETE RESTRICT;

COMMENT ON TABLE communication."ChatConversations" IS 'botglobal-chat:002:20261010172000_AddChatMessageReplies:standalone-postgresql';
COMMIT;
