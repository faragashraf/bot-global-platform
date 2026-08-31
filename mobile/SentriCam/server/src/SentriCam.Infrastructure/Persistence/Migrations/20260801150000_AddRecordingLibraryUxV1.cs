using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace SentriCam.Infrastructure.Persistence.Migrations;

[DbContext(typeof(SentriCamDbContext))]
[Migration("20260801150000_AddRecordingLibraryUxV1")]
public partial class AddRecordingLibraryUxV1 : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.AddColumn<string>(
            name: "ThumbnailErrorCode",
            table: "Recordings",
            type: "nvarchar(100)",
            maxLength: 100,
            nullable: true);

        migrationBuilder.AddColumn<int>(
            name: "ThumbnailGenerationAttempts",
            table: "Recordings",
            type: "int",
            nullable: false,
            defaultValue: 0);

        migrationBuilder.AddColumn<DateTimeOffset>(
            name: "ThumbnailGeneratedUtc",
            table: "Recordings",
            type: "datetimeoffset(7)",
            precision: 7,
            nullable: true);

        migrationBuilder.AddColumn<DateTimeOffset>(
            name: "ThumbnailProcessingStartedUtc",
            table: "Recordings",
            type: "datetimeoffset(7)",
            precision: 7,
            nullable: true);

        migrationBuilder.AddColumn<int>(
            name: "ThumbnailState",
            table: "Recordings",
            type: "int",
            nullable: false,
            defaultValue: 0);

        migrationBuilder.Sql(
            """
            UPDATE [Recordings]
            SET [ThumbnailState] = 2,
                [ThumbnailGenerationAttempts] = 1,
                [ThumbnailGeneratedUtc] = [UploadedUtc]
            WHERE [ThumbnailRelativePath] IS NOT NULL
              AND RIGHT(LOWER([ThumbnailRelativePath]), 4) = '.jpg';
            """);

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
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropIndex(name: "IX_Recordings_DurationMilliseconds_CreatedUtc", table: "Recordings");
        migrationBuilder.DropIndex(name: "IX_Recordings_IsMotion_CreatedUtc", table: "Recordings");
        migrationBuilder.DropIndex(name: "IX_Recordings_SizeBytes_CreatedUtc", table: "Recordings");
        migrationBuilder.DropIndex(name: "IX_Recordings_ThumbnailState_UploadedUtc", table: "Recordings");
        migrationBuilder.DropIndex(name: "IX_Recordings_UploadedUtc", table: "Recordings");
        migrationBuilder.DropColumn(name: "ThumbnailErrorCode", table: "Recordings");
        migrationBuilder.DropColumn(name: "ThumbnailGenerationAttempts", table: "Recordings");
        migrationBuilder.DropColumn(name: "ThumbnailGeneratedUtc", table: "Recordings");
        migrationBuilder.DropColumn(name: "ThumbnailProcessingStartedUtc", table: "Recordings");
        migrationBuilder.DropColumn(name: "ThumbnailState", table: "Recordings");
    }
}
