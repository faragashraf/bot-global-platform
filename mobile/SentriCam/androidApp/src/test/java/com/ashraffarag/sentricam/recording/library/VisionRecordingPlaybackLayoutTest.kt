package com.ashraffarag.sentricam.recording.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionRecordingPlaybackLayoutTest {
    @Test
    fun recordingsComposeSharedVisionHeaderSearchAndCompactFilters() {
        val layout = source("src/main/res/layout/activity_recordings.xml")

        assertTrue(layout.contains("style=\"@style/Widget.SentriCam.Vision.ScreenToolbar\""))
        assertTrue(layout.contains("style=\"@style/Widget.SentriCam.Vision.SearchField\""))
        assertEquals(
            4,
            Regex("style=\"@style/Widget.SentriCam.Vision.FilterChip\"")
                .findAll(layout)
                .count(),
        )
        assertTrue(layout.contains("app:singleSelection=\"true\""))
        assertTrue(layout.contains("app:selectionRequired=\"true\""))
        assertFalse(layout.contains("<HorizontalScrollView"))
    }

    @Test
    fun recordingCardsAreThumbnailLedAndKeepTechnicalMetadataProgressive() {
        val layout = source("src/main/res/layout/item_recording_card.xml")
        val tabletLayout = source("src/main/res/layout-sw600dp/item_recording_card.xml")

        listOf(layout, tabletLayout).forEach { card ->
            assertTrue(card.contains("style=\"@style/Widget.SentriCam.Vision.MediaCard\""))
            assertTrue(card.contains("com.google.android.material.imageview.ShapeableImageView"))
            assertTrue(card.contains("android:id=\"@+id/recording_duration\""))
            assertTrue(card.contains("style=\"@style/Widget.SentriCam.Vision.MediaDurationBadge\""))
            assertTrue(card.contains("android:id=\"@+id/recording_trigger\""))
            assertTrue(card.contains("android:id=\"@+id/recording_quality\""))
            assertTrue(card.contains("android:id=\"@+id/recording_file_size\""))
            assertTrue(card.contains("android:visibility=\"gone\""))
            assertFalse(card.contains("checkable"))
            assertTrue(Regex("android:clickable=\"false\"").findAll(card).count() >= 3)
        }
    }

    @Test
    fun tabletLibraryRetainsTwoColumnResponsiveBehavior() {
        val phone = source("src/main/res/values/integers.xml")
        val tablet = source("src/main/res/values-sw600dp/integers.xml")
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/recording/library/android/ui/RecordingsActivity.kt",
        )

        assertTrue(phone.contains("name=\"recordings_grid_span_count\">1"))
        assertTrue(tablet.contains("name=\"recordings_grid_span_count\">2"))
        assertTrue(activity.contains("resources.getInteger(R.integer.recordings_grid_span_count)"))
        assertTrue(activity.contains("GridLayoutManager(this, spanCount)"))
    }

    @Test
    fun playerControlsGiveSeekItsOwnFullWidthRowAndKeepTimeStableInRtl() {
        val controls = source("src/main/res/layout/view_video_player_surface.xml")
        val seekStart = controls.indexOf("android:id=\"@+id/player_seek\"")
        val actionRowStart = controls.indexOf("<androidx.constraintlayout.widget.ConstraintLayout")

        assertTrue(seekStart >= 0)
        assertTrue(actionRowStart > seekStart)
        assertTrue(element(controls, "player_seek", "</SeekBar>").contains(
            "android:layout_width=\"match_parent\"",
        ))
        assertTrue(controls.contains("android:id=\"@+id/player_time_group\""))
        assertTrue(controls.contains("android:layoutDirection=\"ltr\""))
        assertTrue(controls.contains("@style/Widget.SentriCam.Vision.PlayerPrimaryAction"))
        assertTrue(controls.contains("@style/Widget.SentriCam.Vision.PlayerAction"))
    }

    @Test
    fun playbackPrioritizesThePlayerAndUsesFullWidthPortraitFrames() {
        val layout = source("src/main/res/layout/activity_video_player.xml")
        val policy = source(
            "src/main/java/com/ashraffarag/sentricam/recording/playback/domain/VideoDisplayLayout.kt",
        )

        assertTrue(layout.indexOf("android:id=\"@+id/video_player_card\"") <
            layout.indexOf("android:id=\"@+id/details_scroll\""))
        assertTrue(layout.contains("style=\"@style/Widget.SentriCam.Vision.PlayerSurface\""))
        assertTrue(policy.contains("widthPx = if (portraitScreen) width"))
    }

    @Test
    fun technicalMetadataStartsCollapsedAndDisclosureStateSurvivesRecreation() {
        val layout = source("src/main/res/layout/activity_video_player.xml")
        val activity = source(
            "src/main/java/com/ashraffarag/sentricam/recording/library/android/ui/VideoPlayerActivity.kt",
        )
        val details = element(layout, "technical_details", "</LinearLayout>")

        assertTrue(details.contains("android:visibility=\"gone\""))
        assertTrue(layout.contains("android:id=\"@+id/details_toggle\""))
        assertTrue(activity.contains("binding.detailsToggle.setOnClickListener"))
        assertTrue(activity.contains("renderTechnicalDetails()"))
        assertTrue(activity.contains("outState.putBoolean(STATE_TECHNICAL_DETAILS"))
        assertTrue(activity.contains("savedInstanceState?.getBoolean(STATE_TECHNICAL_DETAILS)"))
    }

    @Test
    fun redesignedLayoutsUseDirectionSafeSpacing() {
        val layouts = listOf(
            "src/main/res/layout/activity_recordings.xml",
            "src/main/res/layout/item_recording_card.xml",
            "src/main/res/layout-sw600dp/item_recording_card.xml",
            "src/main/res/layout/activity_video_player.xml",
            "src/main/res/layout/view_video_player_surface.xml",
            "src/main/res/layout/view_recording_detail.xml",
        ).joinToString("\n", transform = ::source)

        listOf(
            "layout_marginLeft",
            "layout_marginRight",
            "paddingLeft",
            "paddingRight",
        ).forEach { directionSpecificAttribute ->
            assertFalse(layouts.contains(directionSpecificAttribute))
        }
    }

    @Test
    fun sharedPrimitivesAvoidUnsupportedCheckableAndPaddingStyleItems() {
        val styles = source("src/main/res/values/styles.xml")
        val badge = element(
            styles,
            "Widget.SentriCam.Vision.MediaDurationBadge",
            "</style>",
        )

        listOf(
            "Widget.SentriCam.Vision.ScreenToolbar",
            "Widget.SentriCam.Vision.SearchField",
            "Widget.SentriCam.Vision.FilterChip",
            "Widget.SentriCam.Vision.MediaCard",
            "Widget.SentriCam.Vision.PlayerSurface",
            "Widget.SentriCam.Vision.PlayerAction",
        ).forEach { primitive -> assertTrue(styles.contains("name=\"$primitive\"")) }
        assertFalse(styles.contains("<item name=\"checkable\""))
        assertFalse(badge.contains("android:paddingHorizontal"))
        assertFalse(badge.contains("android:paddingVertical"))
        assertTrue(badge.contains("android:paddingStart"))
        assertTrue(badge.contains("android:paddingEnd"))
        assertTrue(badge.contains("android:paddingTop"))
        assertTrue(badge.contains("android:paddingBottom"))
    }

    private fun element(source: String, id: String, following: String): String {
        val start = source.indexOf(id)
        require(start >= 0) { "Missing $id" }
        val end = source.indexOf(following, start).takeIf { it >= 0 } ?: source.length
        return source.substring((start - 240).coerceAtLeast(0), end)
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
