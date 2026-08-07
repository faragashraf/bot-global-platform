using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

#pragma warning disable CA1814 // Prefer jagged arrays over multidimensional

namespace BotGlobal.Catalog.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class InitialCatalog : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "catalog");

            migrationBuilder.CreateTable(
                name: "Products",
                schema: "catalog",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Slug = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    Category = table.Column<string>(type: "varchar(16)", nullable: false),
                    PublicationStatus = table.Column<string>(type: "varchar(16)", nullable: false),
                    IsFeatured = table.Column<bool>(type: "bit", nullable: false),
                    SortOrder = table.Column<int>(type: "int", nullable: false),
                    PublishedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Products", x => x.Id);
                    table.CheckConstraint("CK_Products_Category", "[Category] IN ('app', 'game', 'program')");
                    table.CheckConstraint("CK_Products_FeaturedPublished", "[IsFeatured] = 0 OR [PublicationStatus] = 'Published'");
                    table.CheckConstraint("CK_Products_PublicationStatus", "[PublicationStatus] IN ('Draft', 'Published', 'Archived')");
                    table.CheckConstraint("CK_Products_Slug", "LEN([Slug]) > 0 AND [Slug] COLLATE Latin1_General_100_BIN2 = LOWER([Slug]) AND [Slug] NOT LIKE '%[^a-z0-9-]%' AND [Slug] NOT LIKE '-%' AND [Slug] NOT LIKE '%-' AND [Slug] NOT LIKE '%--%'");
                    table.CheckConstraint("CK_Products_SortOrder", "[SortOrder] >= 0");
                });

            migrationBuilder.CreateTable(
                name: "ProductLinks",
                schema: "catalog",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ProductId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Type = table.Column<string>(type: "varchar(16)", nullable: false),
                    Url = table.Column<string>(type: "nvarchar(2048)", maxLength: 2048, nullable: false),
                    LabelEn = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: true),
                    LabelAr = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: true),
                    SortOrder = table.Column<int>(type: "int", nullable: false),
                    UrlHash = table.Column<byte[]>(type: "binary(32)", nullable: true, computedColumnSql: "CONVERT(binary(32), HASHBYTES('SHA2_256', CONVERT(varbinary(max), [Url])))", stored: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ProductLinks", x => x.Id);
                    table.CheckConstraint("CK_ProductLinks_SortOrder", "[SortOrder] >= 0");
                    table.CheckConstraint("CK_ProductLinks_Type", "[Type] IN ('support', 'privacy', 'store', 'download', 'website')");
                    table.ForeignKey(
                        name: "FK_ProductLinks_Products_ProductId",
                        column: x => x.ProductId,
                        principalSchema: "catalog",
                        principalTable: "Products",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "ProductLocalizations",
                schema: "catalog",
                columns: table => new
                {
                    ProductId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Language = table.Column<string>(type: "char(2)", nullable: false),
                    Name = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: false),
                    ShortDescription = table.Column<string>(type: "nvarchar(600)", maxLength: 600, nullable: false),
                    Description = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    DisplayStatus = table.Column<string>(type: "nvarchar(150)", maxLength: 150, nullable: true),
                    PlatformsJson = table.Column<string>(type: "nvarchar(max)", nullable: false, defaultValueSql: "N'[]'"),
                    TechnologiesJson = table.Column<string>(type: "nvarchar(max)", nullable: false, defaultValueSql: "N'[]'")
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ProductLocalizations", x => new { x.ProductId, x.Language });
                    table.CheckConstraint("CK_ProductLocalizations_Language", "[Language] IN ('en', 'ar')");
                    table.CheckConstraint("CK_ProductLocalizations_PlatformsJson", "ISJSON([PlatformsJson]) = 1 AND LEFT(LTRIM([PlatformsJson]), 1) = '['");
                    table.CheckConstraint("CK_ProductLocalizations_TechnologiesJson", "ISJSON([TechnologiesJson]) = 1 AND LEFT(LTRIM([TechnologiesJson]), 1) = '['");
                    table.ForeignKey(
                        name: "FK_ProductLocalizations_Products_ProductId",
                        column: x => x.ProductId,
                        principalSchema: "catalog",
                        principalTable: "Products",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "ProductMedia",
                schema: "catalog",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ProductId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Kind = table.Column<string>(type: "varchar(16)", nullable: false),
                    StorageProvider = table.Column<string>(type: "nvarchar(40)", maxLength: 40, nullable: false),
                    StorageKey = table.Column<string>(type: "nvarchar(500)", maxLength: 500, nullable: false),
                    ContentType = table.Column<string>(type: "nvarchar(100)", maxLength: 100, nullable: false),
                    ByteLength = table.Column<long>(type: "bigint", nullable: true),
                    Width = table.Column<int>(type: "int", nullable: true),
                    Height = table.Column<int>(type: "int", nullable: true),
                    AltTextEn = table.Column<string>(type: "nvarchar(300)", maxLength: 300, nullable: true),
                    AltTextAr = table.Column<string>(type: "nvarchar(300)", maxLength: 300, nullable: true),
                    SortOrder = table.Column<int>(type: "int", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ProductMedia", x => x.Id);
                    table.CheckConstraint("CK_ProductMedia_ByteLength", "[ByteLength] IS NULL OR [ByteLength] >= 0");
                    table.CheckConstraint("CK_ProductMedia_Height", "[Height] IS NULL OR [Height] > 0");
                    table.CheckConstraint("CK_ProductMedia_Kind", "[Kind] IN ('hero', 'screenshot')");
                    table.CheckConstraint("CK_ProductMedia_SortOrder", "[SortOrder] >= 0");
                    table.CheckConstraint("CK_ProductMedia_Width", "[Width] IS NULL OR [Width] > 0");
                    table.ForeignKey(
                        name: "FK_ProductMedia_Products_ProductId",
                        column: x => x.ProductId,
                        principalSchema: "catalog",
                        principalTable: "Products",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "ProductReleases",
                schema: "catalog",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    ProductId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    Version = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: false),
                    PublicationStatus = table.Column<string>(type: "varchar(16)", nullable: false),
                    ReleasedAtUtc = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: true),
                    NotesEn = table.Column<string>(type: "nvarchar(max)", nullable: true),
                    NotesAr = table.Column<string>(type: "nvarchar(max)", nullable: true),
                    SortOrder = table.Column<int>(type: "int", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ProductReleases", x => x.Id);
                    table.CheckConstraint("CK_ProductReleases_PublicationStatus", "[PublicationStatus] IN ('Draft', 'Published', 'Archived')");
                    table.CheckConstraint("CK_ProductReleases_SortOrder", "[SortOrder] >= 0");
                    table.ForeignKey(
                        name: "FK_ProductReleases_Products_ProductId",
                        column: x => x.ProductId,
                        principalSchema: "catalog",
                        principalTable: "Products",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.InsertData(
                schema: "catalog",
                table: "Products",
                columns: new[] { "Id", "Category", "IsFeatured", "PublicationStatus", "PublishedAtUtc", "Slug", "SortOrder" },
                values: new object[] { new Guid("a5b5930e-8499-4b52-9a76-6cc0de0f4a11"), "app", true, "Published", null, "sentricam", 0 });

            migrationBuilder.InsertData(
                schema: "catalog",
                table: "ProductLocalizations",
                columns: new[] { "Language", "ProductId", "Description", "DisplayStatus", "Name", "PlatformsJson", "ShortDescription", "TechnologiesJson" },
                values: new object[,]
                {
                    { "ar", new Guid("a5b5930e-8499-4b52-9a76-6cc0de0f4a11"), "تُعرّف وثائق منصة BOT GLOBAL منتج SentriCam باعتباره منتجًا قائمًا. لم تُنشر بعد تفاصيل موثقة للعامة حول الميزات أو المنصات أو الوسائط أو الإتاحة أو الدعم؛ لذلك لا يتضمن هذا السجل أي ادعاءات إضافية عن المنتج.", "التفاصيل قيد الإعداد", "SentriCam", "[]", "منتج قائم من BOT GLOBAL، ويجري حاليًا إعداد تفاصيله للنشر في الكتالوج العام.", "[]" },
                    { "en", new Guid("a5b5930e-8499-4b52-9a76-6cc0de0f4a11"), "SentriCam is identified in the BOT GLOBAL platform documentation as an existing product. Verified public feature, platform, media, availability, and support details have not yet been published, so this entry intentionally makes no additional product claims.", "Details pending", "SentriCam", "[]", "An existing BOT GLOBAL product with public catalog details in preparation.", "[]" }
                });

            migrationBuilder.CreateIndex(
                name: "UX_ProductLinks_ProductId_Type_Url",
                schema: "catalog",
                table: "ProductLinks",
                columns: new[] { "ProductId", "Type", "UrlHash" },
                unique: true,
                filter: "[UrlHash] IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "UX_ProductMedia_ProductId_Hero",
                schema: "catalog",
                table: "ProductMedia",
                column: "ProductId",
                unique: true,
                filter: "[Kind] = 'hero'");

            migrationBuilder.CreateIndex(
                name: "UX_ProductMedia_StorageProvider_StorageKey",
                schema: "catalog",
                table: "ProductMedia",
                columns: new[] { "StorageProvider", "StorageKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "UX_ProductReleases_ProductId_Version",
                schema: "catalog",
                table: "ProductReleases",
                columns: new[] { "ProductId", "Version" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "UX_Products_Category_Slug",
                schema: "catalog",
                table: "Products",
                columns: new[] { "Category", "Slug" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "ProductLinks",
                schema: "catalog");

            migrationBuilder.DropTable(
                name: "ProductLocalizations",
                schema: "catalog");

            migrationBuilder.DropTable(
                name: "ProductMedia",
                schema: "catalog");

            migrationBuilder.DropTable(
                name: "ProductReleases",
                schema: "catalog");

            migrationBuilder.DropTable(
                name: "Products",
                schema: "catalog");
        }
    }
}
