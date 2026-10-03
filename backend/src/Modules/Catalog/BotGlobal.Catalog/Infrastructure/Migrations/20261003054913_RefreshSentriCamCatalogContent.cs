using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace BotGlobal.Catalog.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class RefreshSentriCamCatalogContent : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.UpdateData(
                schema: "catalog",
                table: "ProductLocalizations",
                keyColumns: new[] { "Language", "ProductId" },
                keyValues: new object[] { "ar", new Guid("a5b5930e-8499-4b52-9a76-6cc0de0f4a11") },
                columns: new[] { "Description", "DisplayStatus", "PlatformsJson", "ShortDescription", "TechnologiesJson" },
                values: new object[] { "يحول SentriCam هواتف أندرويد إلى أجهزة مراقبة مُدارة تتصل بـ Hub محلي على كمبيوتر في المنزل أو المكتب. رحلة الإعداد تشمل تثبيت الـ Hub، اختيار سياسة التخزين، الاقتران عبر QR، متابعة صحة الجهاز، التحكم في التسجيل، أرشيف التسجيلات المحلي، والبث المباشر داخل الشبكة المحلية. حدود V1 مقصودة: SignalR ينسق الجلسات المصرح بها، بينما يظل الفيديو داخل الشبكة المحلية وتبقى إمكانات السحابة والذكاء الاصطناعي مؤجلة.", "استعراض تشغيلي V1", "[\"\\u062C\\u0647\\u0627\\u0632 \\u0643\\u0627\\u0645\\u064A\\u0631\\u0627 \\u0623\\u0646\\u062F\\u0631\\u0648\\u064A\\u062F\",\"SentriCam Hub\",\"\\u0644\\u0648\\u062D\\u0629 \\u062A\\u062D\\u0643\\u0645 \\u0627\\u0644\\u0645\\u062A\\u0635\\u0641\\u062D\"]", "مراقبة كاميرات محلية للمنازل والفرق الصغيرة، تربط أجهزة أندرويد بلوحة Hub خاصة ولوحة تحكم من المتصفح.", "[\"Kotlin\",\"CameraX\",\"ASP.NET Core\",\"SignalR\",\"\\u0628\\u062B \\u0645\\u0628\\u0627\\u0634\\u0631 WebRTC \\u062F\\u0627\\u062E\\u0644 \\u0627\\u0644\\u0634\\u0628\\u0643\\u0629 \\u0627\\u0644\\u0645\\u062D\\u0644\\u064A\\u0629\"]" });

            migrationBuilder.UpdateData(
                schema: "catalog",
                table: "ProductLocalizations",
                keyColumns: new[] { "Language", "ProductId" },
                keyValues: new object[] { "en", new Guid("a5b5930e-8499-4b52-9a76-6cc0de0f4a11") },
                columns: new[] { "Description", "DisplayStatus", "PlatformsJson", "ShortDescription", "TechnologiesJson" },
                values: new object[] { "SentriCam turns Android phones into managed monitoring devices connected to a local Hub on a home or office computer. The setup journey covers Hub installation, storage policy selection, QR pairing, device health, recording control, local recording archive, and LAN live view. The V1 boundary is deliberately local-first: SignalR coordinates authorized sessions, while video stays on the local network and cloud/AI capabilities remain deferred.", "V1 runtime showcase", "[\"Android camera device\",\"SentriCam Hub\",\"Browser dashboard\"]", "Local-first camera monitoring for homes and small teams, pairing Android camera devices with a private Hub and browser dashboard.", "[\"Kotlin\",\"CameraX\",\"ASP.NET Core\",\"SignalR\",\"WebRTC LAN live view\"]" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.UpdateData(
                schema: "catalog",
                table: "ProductLocalizations",
                keyColumns: new[] { "Language", "ProductId" },
                keyValues: new object[] { "ar", new Guid("a5b5930e-8499-4b52-9a76-6cc0de0f4a11") },
                columns: new[] { "Description", "DisplayStatus", "PlatformsJson", "ShortDescription", "TechnologiesJson" },
                values: new object[] { "تُعرّف وثائق منصة BOT GLOBAL منتج SentriCam باعتباره منتجًا قائمًا. لم تُنشر بعد تفاصيل موثقة للعامة حول الميزات أو المنصات أو الوسائط أو الإتاحة أو الدعم؛ لذلك لا يتضمن هذا السجل أي ادعاءات إضافية عن المنتج.", "التفاصيل قيد الإعداد", "[]", "منتج قائم من BOT GLOBAL، ويجري حاليًا إعداد تفاصيله للنشر في الكتالوج العام.", "[]" });

            migrationBuilder.UpdateData(
                schema: "catalog",
                table: "ProductLocalizations",
                keyColumns: new[] { "Language", "ProductId" },
                keyValues: new object[] { "en", new Guid("a5b5930e-8499-4b52-9a76-6cc0de0f4a11") },
                columns: new[] { "Description", "DisplayStatus", "PlatformsJson", "ShortDescription", "TechnologiesJson" },
                values: new object[] { "SentriCam is identified in the BOT GLOBAL platform documentation as an existing product. Verified public feature, platform, media, availability, and support details have not yet been published, so this entry intentionally makes no additional product claims.", "Details pending", "[]", "An existing BOT GLOBAL product with public catalog details in preparation.", "[]" });
        }
    }
}
