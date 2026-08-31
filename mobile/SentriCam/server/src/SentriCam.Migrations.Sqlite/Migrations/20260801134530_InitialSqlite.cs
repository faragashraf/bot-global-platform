using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace SentriCam.Migrations.Sqlite.Migrations
{
    /// <inheritdoc />
    public partial class InitialSqlite : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "Organizations",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Organizations", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "OutboxMessages",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    EventId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EventType = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    SchemaVersion = table.Column<int>(type: "INTEGER", nullable: false),
                    PayloadJson = table.Column<string>(type: "TEXT", nullable: false),
                    OccurredAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    ProcessedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    AttemptCount = table.Column<int>(type: "INTEGER", nullable: false),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_OutboxMessages", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Sites",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Sites", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Sites_Organizations_OrganizationId",
                        column: x => x.OrganizationId,
                        principalTable: "Organizations",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Areas",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    SiteId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Areas", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Areas_Sites_SiteId",
                        column: x => x.SiteId,
                        principalTable: "Sites",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceGroups",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    AreaId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceGroups", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceGroups_Areas_AreaId",
                        column: x => x.AreaId,
                        principalTable: "Areas",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Devices",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DisplayName = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    InstallationId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    Manufacturer = table.Column<string>(type: "TEXT", maxLength: 100, nullable: false),
                    Model = table.Column<string>(type: "TEXT", maxLength: 100, nullable: false),
                    Platform = table.Column<string>(type: "TEXT", maxLength: 50, nullable: false),
                    OperatingSystemVersion = table.Column<string>(type: "TEXT", maxLength: 50, nullable: false),
                    AppVersion = table.Column<string>(type: "TEXT", maxLength: 50, nullable: false),
                    Status = table.Column<int>(type: "INTEGER", nullable: false),
                    IsEnabled = table.Column<bool>(type: "INTEGER", nullable: false),
                    RegisteredAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    LastSeenAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    DeviceGroupId = table.Column<Guid>(type: "TEXT", nullable: true),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Devices", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Devices_DeviceGroups_DeviceGroupId",
                        column: x => x.DeviceGroupId,
                        principalTable: "DeviceGroups",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.SetNull);
                });

            migrationBuilder.CreateTable(
                name: "DeviceCapabilities",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", maxLength: 100, nullable: false),
                    NormalizedName = table.Column<string>(type: "TEXT", maxLength: 100, nullable: false),
                    Version = table.Column<string>(type: "TEXT", maxLength: 50, nullable: false),
                    IsEnabled = table.Column<bool>(type: "INTEGER", nullable: false),
                    AddedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceCapabilities", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceCapabilities_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceCommands",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Kind = table.Column<int>(type: "INTEGER", nullable: false),
                    CorrelationId = table.Column<string>(type: "TEXT", maxLength: 100, nullable: false),
                    State = table.Column<int>(type: "INTEGER", nullable: false),
                    RequestedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    ExpiresAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    Origin = table.Column<int>(type: "INTEGER", nullable: false),
                    DispatchedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    CompletedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    FailureReason = table.Column<string>(type: "TEXT", maxLength: 500, nullable: true),
                    ResultCode = table.Column<string>(type: "TEXT", maxLength: 100, nullable: true),
                    ResultJson = table.Column<string>(type: "TEXT", nullable: true),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceCommands", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceCommands_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceConnections",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    TransportConnectionId = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    ConnectedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    LastSeenAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    DisconnectedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    IsActive = table.Column<bool>(type: "INTEGER", nullable: false),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceConnections", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceConnections_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceEvents",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    EventType = table.Column<string>(type: "TEXT", maxLength: 100, nullable: false),
                    PayloadJson = table.Column<string>(type: "TEXT", nullable: true),
                    OccurredAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    ReceivedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceEvents", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceEvents_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceRegistrations",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Kind = table.Column<int>(type: "INTEGER", nullable: false),
                    ClientAppVersion = table.Column<string>(type: "TEXT", maxLength: 50, nullable: false),
                    RegisteredAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceRegistrations", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceRegistrations_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceSnapshots",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Status = table.Column<int>(type: "INTEGER", nullable: false),
                    BatteryPercentage = table.Column<int>(type: "INTEGER", nullable: true),
                    AvailableStorageBytes = table.Column<long>(type: "INTEGER", nullable: true),
                    IsMonitoring = table.Column<bool>(type: "INTEGER", nullable: false),
                    IsRecording = table.Column<bool>(type: "INTEGER", nullable: false),
                    MetadataJson = table.Column<string>(type: "TEXT", nullable: true),
                    SnapshotVersion = table.Column<long>(type: "INTEGER", nullable: false),
                    SchemaVersion = table.Column<int>(type: "INTEGER", nullable: false),
                    GeneratedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    CapturedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceSnapshots", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceSnapshots_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "DeviceStatusHistory",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    PreviousStatus = table.Column<int>(type: "INTEGER", nullable: true),
                    CurrentStatus = table.Column<int>(type: "INTEGER", nullable: false),
                    Reason = table.Column<string>(type: "TEXT", maxLength: 500, nullable: true),
                    ChangedAtUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeviceStatusHistory", x => x.Id);
                    table.ForeignKey(
                        name: "FK_DeviceStatusHistory_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Recordings",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    ClientRecordingId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    SessionId = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    OriginalFileName = table.Column<string>(type: "TEXT", maxLength: 255, nullable: false),
                    ContentType = table.Column<string>(type: "TEXT", maxLength: 100, nullable: false),
                    DurationMilliseconds = table.Column<long>(type: "INTEGER", nullable: false),
                    SizeBytes = table.Column<long>(type: "INTEGER", nullable: false),
                    CreatedUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    UploadedUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: false),
                    IsMotion = table.Column<bool>(type: "INTEGER", nullable: false),
                    IsManual = table.Column<bool>(type: "INTEGER", nullable: false),
                    ChecksumSha256 = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    RelativePath = table.Column<string>(type: "TEXT", maxLength: 1024, nullable: false),
                    ThumbnailRelativePath = table.Column<string>(type: "TEXT", maxLength: 1024, nullable: true),
                    ThumbnailState = table.Column<int>(type: "INTEGER", nullable: false),
                    ThumbnailGenerationAttempts = table.Column<int>(type: "INTEGER", nullable: false),
                    ThumbnailErrorCode = table.Column<string>(type: "TEXT", maxLength: 100, nullable: true),
                    ThumbnailGeneratedUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    ThumbnailProcessingStartedUtc = table.Column<DateTimeOffset>(type: "TEXT", precision: 7, nullable: true),
                    RowVersion = table.Column<byte[]>(type: "BLOB", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Recordings", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Recordings_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "UX_Areas_SiteId_Name",
                table: "Areas",
                columns: new[] { "SiteId", "Name" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "UX_DeviceCapabilities_DeviceId_NormalizedName",
                table: "DeviceCapabilities",
                columns: new[] { "DeviceId", "NormalizedName" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_DeviceCommands_DeviceId_State_RequestedAtUtc",
                table: "DeviceCommands",
                columns: new[] { "DeviceId", "State", "RequestedAtUtc" },
                descending: new[] { false, false, true });

            migrationBuilder.CreateIndex(
                name: "UX_DeviceCommands_DeviceId_CorrelationId",
                table: "DeviceCommands",
                columns: new[] { "DeviceId", "CorrelationId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_DeviceConnections_DeviceId_LastSeenAtUtc",
                table: "DeviceConnections",
                columns: new[] { "DeviceId", "LastSeenAtUtc" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "UX_DeviceConnections_OneActivePerDevice",
                table: "DeviceConnections",
                columns: new[] { "DeviceId", "IsActive" },
                unique: true,
                filter: "\"IsActive\" = 1");

            migrationBuilder.CreateIndex(
                name: "UX_DeviceConnections_TransportConnectionId",
                table: "DeviceConnections",
                column: "TransportConnectionId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_DeviceEvents_DeviceId_OccurredAtUtc",
                table: "DeviceEvents",
                columns: new[] { "DeviceId", "OccurredAtUtc" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "IX_DeviceEvents_EventType_ReceivedAtUtc",
                table: "DeviceEvents",
                columns: new[] { "EventType", "ReceivedAtUtc" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "UX_DeviceGroups_AreaId_Name",
                table: "DeviceGroups",
                columns: new[] { "AreaId", "Name" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_DeviceRegistrations_DeviceId_RegisteredAtUtc",
                table: "DeviceRegistrations",
                columns: new[] { "DeviceId", "RegisteredAtUtc" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "IX_Devices_DeviceGroupId",
                table: "Devices",
                column: "DeviceGroupId");

            migrationBuilder.CreateIndex(
                name: "IX_Devices_Status_LastSeenAtUtc",
                table: "Devices",
                columns: new[] { "Status", "LastSeenAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_Devices_InstallationId",
                table: "Devices",
                column: "InstallationId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_DeviceSnapshots_DeviceId_CapturedAtUtc",
                table: "DeviceSnapshots",
                columns: new[] { "DeviceId", "CapturedAtUtc" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "UX_DeviceSnapshots_DeviceId_SnapshotVersion",
                table: "DeviceSnapshots",
                columns: new[] { "DeviceId", "SnapshotVersion" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_DeviceStatusHistory_DeviceId_ChangedAtUtc",
                table: "DeviceStatusHistory",
                columns: new[] { "DeviceId", "ChangedAtUtc" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "UX_Organizations_Name",
                table: "Organizations",
                column: "Name",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_OutboxMessages_ProcessedAtUtc_CreatedAtUtc",
                table: "OutboxMessages",
                columns: new[] { "ProcessedAtUtc", "CreatedAtUtc" });

            migrationBuilder.CreateIndex(
                name: "UX_OutboxMessages_EventId",
                table: "OutboxMessages",
                column: "EventId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_ChecksumSha256",
                table: "Recordings",
                column: "ChecksumSha256");

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_CreatedUtc",
                table: "Recordings",
                column: "CreatedUtc",
                descending: new bool[0]);

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_DeviceId_CreatedUtc",
                table: "Recordings",
                columns: new[] { "DeviceId", "CreatedUtc" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_DurationMilliseconds_CreatedUtc",
                table: "Recordings",
                columns: new[] { "DurationMilliseconds", "CreatedUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_IsMotion_CreatedUtc",
                table: "Recordings",
                columns: new[] { "IsMotion", "CreatedUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_SizeBytes_CreatedUtc",
                table: "Recordings",
                columns: new[] { "SizeBytes", "CreatedUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_ThumbnailState_UploadedUtc",
                table: "Recordings",
                columns: new[] { "ThumbnailState", "UploadedUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_UploadedUtc",
                table: "Recordings",
                column: "UploadedUtc",
                descending: new bool[0]);

            migrationBuilder.CreateIndex(
                name: "UX_Recordings_DeviceId_ClientRecordingId",
                table: "Recordings",
                columns: new[] { "DeviceId", "ClientRecordingId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "UX_Sites_OrganizationId_Name",
                table: "Sites",
                columns: new[] { "OrganizationId", "Name" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "DeviceCapabilities");

            migrationBuilder.DropTable(
                name: "DeviceCommands");

            migrationBuilder.DropTable(
                name: "DeviceConnections");

            migrationBuilder.DropTable(
                name: "DeviceEvents");

            migrationBuilder.DropTable(
                name: "DeviceRegistrations");

            migrationBuilder.DropTable(
                name: "DeviceSnapshots");

            migrationBuilder.DropTable(
                name: "DeviceStatusHistory");

            migrationBuilder.DropTable(
                name: "OutboxMessages");

            migrationBuilder.DropTable(
                name: "Recordings");

            migrationBuilder.DropTable(
                name: "Devices");

            migrationBuilder.DropTable(
                name: "DeviceGroups");

            migrationBuilder.DropTable(
                name: "Areas");

            migrationBuilder.DropTable(
                name: "Sites");

            migrationBuilder.DropTable(
                name: "Organizations");
        }
    }
}
