package com.ashraffarag.sentricam.localization

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationArchitectureTest {
    @Test
    fun englishFallbackAndArabicResourcesCoverEveryTranslatableString() {
        val english = source("src/main/res/values/strings.xml")
        val arabic = source("src/main/res/values-ar/strings.xml")
        val englishResources = stringResources(english)
        val arabicResources = stringResources(arabic)
        val requiredArabic = englishResources.filterValues { it }.keys

        assertTrue(englishResources.size >= 450)
        assertTrue(requiredArabic.minus(arabicResources.keys).isEmpty())
        assertTrue(arabicResources.keys.minus(englishResources.keys).isEmpty())
        assertFalse(english.contains("tools:ignore=\"MissingTranslation\""))
        assertFalse(File("src/main/res/values-en/strings.xml").exists())
        assertTrue(arabic.contains("<string name=\"settings_title\">الإعدادات</string>"))
        assertTrue(arabic.contains("<string name=\"recording_active\">جارٍ التسجيل</string>"))
        assertTrue(english.contains("<string name=\"recording_active\">Recording in progress</string>"))
    }

    @Test
    fun productionLayoutsUseResourcesForUserVisibleTextAndAccessibility() {
        val hardCoded = File("src/main/res")
            .walkTopDown()
            .filter {
                it.isFile && it.extension == "xml" &&
                    it.parentFile?.name.orEmpty().startsWith("layout")
            }
            .flatMap { file ->
                USER_TEXT_ATTRIBUTE.findAll(file.readText()).mapNotNull { match ->
                    val value = match.groupValues[2]
                    if (value.isBlank() || value.startsWith("@") || value.startsWith("?")) {
                        null
                    } else {
                        "${file.path}: ${match.value}"
                    }
                }
            }
            .toList()

        assertTrue(hardCoded.joinToString("\n"), hardCoded.isEmpty())
    }

    @Test
    fun topLanguageActionReusesTheExistingMotionControllerInTheLowerDock() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val activity = source("src/main/java/com/ashraffarag/sentricam/MainActivity.kt")
        val toolbar = element(layout, "camera_toolbar", "operational_status_cluster")
        val dock = element(layout, "recording_panel", "status_panel")

        assertTrue(toolbar.contains("android:id=\"@+id/language_button\""))
        assertTrue(toolbar.contains("app:icon=\"@drawable/ic_language_24\""))
        assertTrue(toolbar.contains("@string/language_action_content_description"))
        assertFalse(toolbar.contains("motion_settings_button"))
        assertTrue(dock.contains("android:id=\"@+id/motion_settings_button\""))
        assertTrue(dock.contains("app:icon=\"@drawable/ic_motion_24\""))
        assertTrue(dock.contains("android:id=\"@+id/settings_button\""))
        assertTrue(activity.contains(
            "motionToolbarButtonController = MotionToolbarButtonController(binding.motionSettingsButton)",
        ))
        assertTrue(activity.contains("primaryButton = binding.motionSettingsButton"))
        assertTrue(activity.contains("binding.languageButton.setOnClickListener"))
        assertTrue(activity.contains(
            "binding.languageButton.isEnabled = canOpenSettings(state)",
        ))
    }

    @Test
    fun criticalVisionGeometryAndNumericPlaybackRemainDirectionSafe() {
        val live = source("src/main/res/layout/activity_main.xml")
        val shutter = element(live, "recording_button", "camera_switch_button")
        val playback = source("src/main/res/layout/view_video_player_surface.xml")
        val timeGroup = element(playback, "player_time_group", "full_screen_button")

        assertTrue(shutter.contains("app:layout_constraintStart_toStartOf=\"parent\""))
        assertTrue(shutter.contains("app:layout_constraintEnd_toEndOf=\"parent\""))
        assertFalse(shutter.contains("layout_marginStart"))
        assertFalse(shutter.contains("layout_marginEnd"))
        assertFalse(live.contains("layout_marginLeft"))
        assertFalse(live.contains("layout_marginRight"))
        assertFalse(live.contains("paddingLeft"))
        assertFalse(live.contains("paddingRight"))
        assertTrue(timeGroup.contains("android:layoutDirection=\"ltr\""))
        assertTrue(timeGroup.contains("@string/recording_duration_zero"))
    }

    @Test
    fun localeSelectionIsCentralizedAndForegroundTextIsResourceBacked() {
        val application = source("src/main/java/com/ashraffarag/sentricam/SentriCamApplication.kt")
        val androidLocale = source(
            "src/main/java/com/ashraffarag/sentricam/localization/android/AndroidAppLanguage.kt",
        )
        val notificationMapper = source(
            "src/main/java/com/ashraffarag/sentricam/monitoring/domain/MonitoringNotificationContent.kt",
        )

        assertTrue(application.contains("restorePersistedLanguage"))
        assertTrue(androidLocale.contains("AppCompatDelegate.setApplicationLocales"))
        assertTrue(androidLocale.contains("selected_language_tag"))
        assertFalse(notificationMapper.contains("\"Motion:"))
        assertFalse(notificationMapper.contains("\"Recording:"))
        assertFalse(notificationMapper.contains("\"Monitoring is running\""))
    }

    private fun stringResources(xml: String): Map<String, Boolean> = STRING_RESOURCE
        .findAll(xml)
        .associate { match ->
            match.groupValues[1] to !match.groupValues[2].contains("translatable=\"false\"")
        }

    private fun source(path: String): String = File(path).readText()

    private fun element(layout: String, id: String, nextId: String): String = layout
        .substringAfter("android:id=\"@+id/$id\"")
        .substringBefore("android:id=\"@+id/$nextId\"")

    private companion object {
        val STRING_RESOURCE = Regex("""<string\s+name="([^"]+)"([^>]*)>""")
        val USER_TEXT_ATTRIBUTE = Regex(
            """android:(text|hint|contentDescription|title|summary|label)="([^"]*)""",
        )
    }
}
