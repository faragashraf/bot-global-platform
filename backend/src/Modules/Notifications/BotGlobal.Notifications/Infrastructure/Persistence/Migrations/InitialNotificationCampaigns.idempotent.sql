IF OBJECT_ID(N'[notifications].[__EFMigrationsHistory]') IS NULL
BEGIN
    IF SCHEMA_ID(N'notifications') IS NULL EXEC(N'CREATE SCHEMA [notifications];');
    CREATE TABLE [notifications].[__EFMigrationsHistory] (
        [MigrationId] nvarchar(150) NOT NULL,
        [ProductVersion] nvarchar(32) NOT NULL,
        CONSTRAINT [PK___EFMigrationsHistory] PRIMARY KEY ([MigrationId])
    );
END;
GO
BEGIN TRANSACTION;
IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    IF SCHEMA_ID(N'notifications') IS NULL EXEC(N'CREATE SCHEMA [notifications];');
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    CREATE TABLE [notifications].[NotificationCampaigns] (
        [Id] uniqueidentifier NOT NULL,
        [PlatformClientId] uniqueidentifier NOT NULL,
        [PlatformClientKeySnapshot] nvarchar(100) NOT NULL,
        [PlatformClientDisplayNameSnapshot] nvarchar(200) NOT NULL,
        [AudienceKind] int NOT NULL,
        [AudienceAsOfUtc] datetimeoffset NOT NULL,
        [TitleAr] nvarchar(200) NOT NULL,
        [TitleEn] nvarchar(200) NOT NULL,
        [BodyAr] nvarchar(4000) NOT NULL,
        [BodyEn] nvarchar(4000) NOT NULL,
        [Type] nvarchar(100) NOT NULL,
        [Priority] int NOT NULL,
        [Status] int NOT NULL,
        [IdempotencyKey] varchar(200) NOT NULL,
        [RequestFingerprint] varchar(64) NOT NULL,
        [CreatedByUserId] uniqueidentifier NOT NULL,
        [CreatedByDisplayNameSnapshot] nvarchar(200) NOT NULL,
        [CreatedAtUtc] datetimeoffset NOT NULL,
        [ExpiresAtUtc] datetimeoffset NOT NULL,
        [ProcessingStartedAtUtc] datetimeoffset NULL,
        [CompletedAtUtc] datetimeoffset NULL,
        [AudienceSubjectCount] int NOT NULL,
        [AudienceDeviceCount] int NOT NULL,
        [PushCapableDeviceCount] int NOT NULL,
        [PendingCount] int NOT NULL,
        [SignalRDispatchedCount] int NOT NULL,
        [FcmAcceptedCount] int NOT NULL,
        [FailedCount] int NOT NULL,
        [SkippedCount] int NOT NULL,
        [ExpiredCount] int NOT NULL,
        [AudienceExpansionCursor] uniqueidentifier NULL,
        [IsAudienceExpansionComplete] bit NOT NULL,
        [AudienceLeaseId] uniqueidentifier NULL,
        [AudienceLeaseExpiresAtUtc] datetimeoffset NULL,
        [RowVersion] rowversion NOT NULL,
        CONSTRAINT [PK_NotificationCampaigns] PRIMARY KEY ([Id]),
        CONSTRAINT [CK_NotificationCampaigns_AudienceKind] CHECK ([AudienceKind] = 1),
        CONSTRAINT [CK_NotificationCampaigns_AudienceLease] CHECK (([AudienceLeaseId] IS NULL AND [AudienceLeaseExpiresAtUtc] IS NULL) OR ([AudienceLeaseId] IS NOT NULL AND [AudienceLeaseExpiresAtUtc] IS NOT NULL)),
        CONSTRAINT [CK_NotificationCampaigns_Counts] CHECK ([AudienceSubjectCount] >= 0 AND [AudienceDeviceCount] >= 0 AND [PushCapableDeviceCount] >= 0 AND [PendingCount] >= 0 AND [SignalRDispatchedCount] >= 0 AND [FcmAcceptedCount] >= 0 AND [FailedCount] >= 0 AND [SkippedCount] >= 0 AND [ExpiredCount] >= 0),
        CONSTRAINT [CK_NotificationCampaigns_Lifetime] CHECK ([ExpiresAtUtc] > [CreatedAtUtc]),
        CONSTRAINT [CK_NotificationCampaigns_Priority] CHECK ([Priority] IN (1, 2)),
        CONSTRAINT [CK_NotificationCampaigns_Status] CHECK ([Status] BETWEEN 1 AND 7)
    );
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    CREATE TABLE [notifications].[NotificationRecipients] (
        [Id] uniqueidentifier NOT NULL,
        [CampaignId] uniqueidentifier NOT NULL,
        [MobileDeviceId] uniqueidentifier NOT NULL,
        [InstallationIdSnapshot] nvarchar(200) NOT NULL,
        [PlatformSnapshot] nvarchar(50) NOT NULL,
        [DeviceNameSnapshot] nvarchar(250) NULL,
        [Status] int NOT NULL,
        [AttemptCount] int NOT NULL,
        [NextAttemptAtUtc] datetimeoffset NULL,
        [LastAttemptAtUtc] datetimeoffset NULL,
        [LastTransport] varchar(32) NULL,
        [LastSafeErrorCode] varchar(100) NULL,
        [DispatchedAtUtc] datetimeoffset NULL,
        [ExpiresAtUtc] datetimeoffset NOT NULL,
        [LeaseId] uniqueidentifier NULL,
        [LeaseExpiresAtUtc] datetimeoffset NULL,
        [RowVersion] rowversion NOT NULL,
        CONSTRAINT [PK_NotificationRecipients] PRIMARY KEY ([Id]),
        CONSTRAINT [CK_NotificationRecipients_AttemptCount] CHECK ([AttemptCount] >= 0),
        CONSTRAINT [CK_NotificationRecipients_Lease] CHECK (([LeaseId] IS NULL AND [LeaseExpiresAtUtc] IS NULL) OR ([LeaseId] IS NOT NULL AND [LeaseExpiresAtUtc] IS NOT NULL)),
        CONSTRAINT [CK_NotificationRecipients_NextAttempt] CHECK (([Status] IN (1, 2) AND [NextAttemptAtUtc] IS NOT NULL) OR ([Status] IN (3, 4, 5, 6, 7) AND [NextAttemptAtUtc] IS NULL)),
        CONSTRAINT [CK_NotificationRecipients_Status] CHECK ([Status] BETWEEN 1 AND 7),
        CONSTRAINT [FK_NotificationRecipients_NotificationCampaigns_CampaignId] FOREIGN KEY ([CampaignId]) REFERENCES [notifications].[NotificationCampaigns] ([Id]) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    CREATE INDEX [IX_NotificationCampaigns_AudienceWork] ON [notifications].[NotificationCampaigns] ([Status], [AudienceLeaseExpiresAtUtc]);
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    CREATE INDEX [IX_NotificationCampaigns_PlatformClient_CreatedAtUtc] ON [notifications].[NotificationCampaigns] ([PlatformClientId], [CreatedAtUtc]);
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    CREATE UNIQUE INDEX [UX_NotificationCampaigns_Admin_IdempotencyKey] ON [notifications].[NotificationCampaigns] ([CreatedByUserId], [IdempotencyKey]);
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    CREATE INDEX [IX_NotificationRecipients_DispatchWork] ON [notifications].[NotificationRecipients] ([Status], [NextAttemptAtUtc], [LeaseExpiresAtUtc]);
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    CREATE UNIQUE INDEX [UX_NotificationRecipients_Campaign_Device] ON [notifications].[NotificationRecipients] ([CampaignId], [MobileDeviceId]);
END;

IF NOT EXISTS (
    SELECT * FROM [notifications].[__EFMigrationsHistory]
    WHERE [MigrationId] = N'20260821164303_InitialNotificationCampaigns'
)
BEGIN
    INSERT INTO [notifications].[__EFMigrationsHistory] ([MigrationId], [ProductVersion])
    VALUES (N'20260821164303_InitialNotificationCampaigns', N'10.0.10');
END;

COMMIT;
GO
