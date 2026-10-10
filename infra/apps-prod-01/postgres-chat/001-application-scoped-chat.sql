-- Chat activation v001. Controlled application only; NEVER a startup/init script.
-- Review and record this file's SHA-256 outside the database before execution.
-- Require PostgreSQL 18+, UTF8, an approved Communication database and no Chat objects.
-- Session precondition: SET botglobal.chat_activation_database = '<approved database>';
-- Session precondition: SET botglobal.chat_activation_version = '001';
-- With psql use ON_ERROR_STOP=1. On any failure ROLLBACK/disconnect; never continue.
-- History: the ChatConversations comment is this standalone version's marker. No EF
-- history is inserted, and no claim is made about earlier SQL Server migrations.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET LOCAL search_path = pg_catalog;

DO $guard$
BEGIN
    IF current_setting('server_version_num')::integer < 180000
       OR current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'chat_activation_requires_postgresql18_utf8';
    END IF;
    IF current_setting('botglobal.chat_activation_database', true) IS DISTINCT FROM current_database()
       OR current_setting('botglobal.chat_activation_version', true) IS DISTINCT FROM '001' THEN
        RAISE EXCEPTION 'chat_activation_target_or_version_not_approved';
    END IF;
    IF NOT pg_try_advisory_xact_lock(20261007, 153116) THEN
        RAISE EXCEPTION 'chat_activation_already_running';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_collation c JOIN pg_namespace n ON n.oid = c.collnamespace
                   WHERE n.nspname = 'pg_catalog' AND c.collname = 'C' AND c.collisdeterministic) THEN
        RAISE EXCEPTION 'chat_activation_requires_deterministic_c_collation';
    END IF;
    -- Reject repeated, partial, foreign or mismatched objects, even if they look compatible.
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'communication' AND c.relname IN
                 ('ChatConversations', 'ChatMessages', 'ChatReceipts', 'ChatDispatches', 'ChatVoiceTransfers')) THEN
        RAISE EXCEPTION 'chat_activation_existing_objects_rejected';
    END IF;
    IF to_regclass('communication."__EFMigrationsHistory"') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM communication."__EFMigrationsHistory"
                   WHERE "MigrationId" = '20261007153116_AddApplicationScopedChat') THEN
            RAISE EXCEPTION 'chat_activation_existing_ef_history_rejected';
        END IF;
    END IF;
END;
$guard$;

CREATE SCHEMA IF NOT EXISTS communication;

CREATE TABLE communication."ChatConversations" (
    "Id" uuid NOT NULL,
    "ApplicationId" uuid NOT NULL,
    "DirectPairKey" character varying(420) COLLATE pg_catalog."C" NOT NULL,
    "FirstSubjectId" character varying(200) COLLATE pg_catalog."C" NOT NULL,
    "SecondSubjectId" character varying(200) COLLATE pg_catalog."C" NOT NULL,
    "NextSequence" bigint NOT NULL,
    "Version" bigint NOT NULL,
    "CreatedAtUtc" timestamp with time zone NOT NULL,
    "UpdatedAtUtc" timestamp with time zone NOT NULL,
    CONSTRAINT "PK_ChatConversations" PRIMARY KEY ("Id"),
    CONSTRAINT "AK_ChatConversations_ApplicationId_Id" UNIQUE ("ApplicationId", "Id")
);

CREATE TABLE communication."ChatMessages" (
    "Id" uuid NOT NULL,
    "ApplicationId" uuid NOT NULL,
    "ConversationId" uuid NOT NULL,
    "Sequence" bigint NOT NULL,
    "SenderSubjectId" character varying(200) COLLATE pg_catalog."C" NOT NULL,
    "ClientMessageId" character varying(100) COLLATE pg_catalog."C" NOT NULL,
    "Kind" integer NOT NULL,
    "PayloadFingerprint" character varying(64) NOT NULL,
    "Text" character varying(4000),
    "VoiceTransferId" uuid,
    "CreatedAtUtc" timestamp with time zone NOT NULL,
    CONSTRAINT "PK_ChatMessages" PRIMARY KEY ("Id"),
    CONSTRAINT "AK_ChatMessages_ApplicationId_Id" UNIQUE ("ApplicationId", "Id"),
    CONSTRAINT "FK_ChatMessages_ChatConversations_ApplicationId_ConversationId"
        FOREIGN KEY ("ApplicationId", "ConversationId") REFERENCES communication."ChatConversations" ("ApplicationId", "Id") ON DELETE RESTRICT
);

CREATE TABLE communication."ChatReceipts" (
    "ApplicationId" uuid NOT NULL,
    "ConversationId" uuid NOT NULL,
    "SubjectId" character varying(200) COLLATE pg_catalog."C" NOT NULL,
    "LastReadSequence" bigint NOT NULL,
    "UpdatedAtUtc" timestamp with time zone NOT NULL,
    CONSTRAINT "PK_ChatReceipts" PRIMARY KEY ("ApplicationId", "ConversationId", "SubjectId"),
    CONSTRAINT "FK_ChatReceipts_ChatConversations_ApplicationId_ConversationId"
        FOREIGN KEY ("ApplicationId", "ConversationId") REFERENCES communication."ChatConversations" ("ApplicationId", "Id") ON DELETE RESTRICT
);

CREATE TABLE communication."ChatDispatches" (
    "Id" uuid NOT NULL,
    "ApplicationId" uuid NOT NULL,
    "MessageId" uuid NOT NULL,
    "LeaseId" uuid,
    "RouteOutcomes" character varying(16000) NOT NULL,
    "HintsSent" boolean NOT NULL,
    "RecipientSubjectId" character varying(200) COLLATE pg_catalog."C" NOT NULL,
    "State" integer NOT NULL,
    "AttemptCount" integer NOT NULL,
    "NextAttemptAtUtc" timestamp with time zone NOT NULL,
    "SafeErrorCode" character varying(120),
    CONSTRAINT "PK_ChatDispatches" PRIMARY KEY ("Id"),
    CONSTRAINT "FK_ChatDispatches_ChatMessages_ApplicationId_MessageId"
        FOREIGN KEY ("ApplicationId", "MessageId") REFERENCES communication."ChatMessages" ("ApplicationId", "Id") ON DELETE RESTRICT
);

CREATE TABLE communication."ChatVoiceTransfers" (
    "Id" uuid NOT NULL,
    "ApplicationId" uuid NOT NULL,
    "ConversationId" uuid NOT NULL,
    "MessageId" uuid,
    "SenderSubjectId" character varying(200) COLLATE pg_catalog."C" NOT NULL,
    "RecipientSubjectId" character varying(200) COLLATE pg_catalog."C" NOT NULL,
    "FileKey" character varying(160) COLLATE pg_catalog."C" NOT NULL,
    "Sha256" character varying(64) NOT NULL,
    "Length" bigint NOT NULL,
    "DurationMilliseconds" integer NOT NULL,
    "State" integer NOT NULL,
    "PublishedAtUtc" timestamp with time zone NOT NULL,
    "ExpiresAtUtc" timestamp with time zone NOT NULL,
    "AcknowledgedAtUtc" timestamp with time zone,
    "AcknowledgedInstallationId" uuid,
    "DeletedAtUtc" timestamp with time zone,
    "DeleteAttempts" integer NOT NULL,
    "SafeDeleteError" character varying(120),
    CONSTRAINT "PK_ChatVoiceTransfers" PRIMARY KEY ("Id"),
    CONSTRAINT "AK_ChatVoiceTransfers_ApplicationId_Id" UNIQUE ("ApplicationId", "Id"),
    CONSTRAINT "FK_ChatVoiceTransfers_ChatConversations_App_Conversation"
        FOREIGN KEY ("ApplicationId", "ConversationId") REFERENCES communication."ChatConversations" ("ApplicationId", "Id") ON DELETE RESTRICT,
    CONSTRAINT "FK_ChatVoiceTransfers_ChatMessages_ApplicationId_MessageId"
        FOREIGN KEY ("ApplicationId", "MessageId") REFERENCES communication."ChatMessages" ("ApplicationId", "Id") ON DELETE RESTRICT
);

CREATE UNIQUE INDEX "IX_ChatConversations_ApplicationId_DirectPairKey" ON communication."ChatConversations" ("ApplicationId", "DirectPairKey");
CREATE INDEX "IX_ChatConversations_ApplicationId_FirstSubjectId_UpdatedAtUtc" ON communication."ChatConversations" ("ApplicationId", "FirstSubjectId", "UpdatedAtUtc");
CREATE INDEX "IX_ChatConversations_ApplicationId_SecondSubjectId_UpdatedAtUtc" ON communication."ChatConversations" ("ApplicationId", "SecondSubjectId", "UpdatedAtUtc");
CREATE UNIQUE INDEX "IX_ChatMessages_ApplicationId_ConversationId_Sequence" ON communication."ChatMessages" ("ApplicationId", "ConversationId", "Sequence");
CREATE UNIQUE INDEX "IX_ChatMessages_ApplicationId_SenderSubjectId_ClientMessageId" ON communication."ChatMessages" ("ApplicationId", "SenderSubjectId", "ClientMessageId");
CREATE UNIQUE INDEX "IX_ChatDispatches_ApplicationId_MessageId" ON communication."ChatDispatches" ("ApplicationId", "MessageId");
CREATE INDEX "IX_ChatDispatches_State_NextAttemptAtUtc" ON communication."ChatDispatches" ("State", "NextAttemptAtUtc");
CREATE INDEX "IX_ChatVoiceTransfers_ApplicationId_ConversationId" ON communication."ChatVoiceTransfers" ("ApplicationId", "ConversationId");
CREATE UNIQUE INDEX "IX_ChatVoiceTransfers_ApplicationId_FileKey" ON communication."ChatVoiceTransfers" ("ApplicationId", "FileKey");
CREATE UNIQUE INDEX "IX_ChatVoiceTransfers_ApplicationId_MessageId" ON communication."ChatVoiceTransfers" ("ApplicationId", "MessageId");
CREATE INDEX "IX_ChatVoiceTransfers_State_ExpiresAtUtc" ON communication."ChatVoiceTransfers" ("State", "ExpiresAtUtc");

COMMENT ON TABLE communication."ChatConversations" IS 'botglobal-chat:001:20261007153116_AddApplicationScopedChat:standalone-postgresql';
COMMIT;
