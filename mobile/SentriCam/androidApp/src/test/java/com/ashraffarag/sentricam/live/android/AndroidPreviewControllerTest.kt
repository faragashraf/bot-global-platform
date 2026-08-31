package com.ashraffarag.sentricam.live.android

import androidx.camera.core.Preview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPreviewControllerTest {
    @Test
    fun surfaceProviderCanAttachDetachAndReattachWithoutChangingVisibility() {
        val controller = AndroidPreviewController()
        val first = Preview.SurfaceProvider { }
        val second = Preview.SurfaceProvider { }
        val observed = mutableListOf<Preview.SurfaceProvider?>()
        val subscription = controller.observeSurfaceProvider(observed::add)

        controller.attachSurfaceProvider(first)
        controller.detachSurfaceProvider(first)
        controller.attachSurfaceProvider(second)

        assertNull(observed[0])
        assertSame(first, observed[1])
        assertNull(observed[2])
        assertSame(second, observed[3])
        assertTrue(controller.visible.value)

        subscription.close()
    }

    @Test
    fun detachingAnOldSurfaceDoesNotClearTheCurrentSurface() {
        val controller = AndroidPreviewController()
        val first = Preview.SurfaceProvider { }
        val second = Preview.SurfaceProvider { }
        val observed = mutableListOf<Preview.SurfaceProvider?>()
        controller.observeSurfaceProvider(observed::add)

        controller.attachSurfaceProvider(first)
        controller.attachSurfaceProvider(second)
        controller.detachSurfaceProvider(first)

        assertEquals(3, observed.size)
        assertSame(second, observed.last())
    }

    @Test
    fun hiddenThenVisibleWithdrawsAndReissuesTheSameSurfaceProvider() {
        val controller = AndroidPreviewController()
        val provider = Preview.SurfaceProvider { }
        val observed = mutableListOf<Preview.SurfaceProvider?>()
        controller.attachSurfaceProvider(provider)
        controller.observeSurfaceProvider(observed::add)

        controller.setVisible(false)
        assertFalse(controller.visible.value)
        controller.setVisible(true)

        assertTrue(controller.visible.value)
        assertSame(provider, observed[0])
        assertNull(observed[1])
        assertSame(provider, observed[2])
    }

    @Test
    fun attachingSurfaceWhileHiddenDefersItUntilVisible() {
        val controller = AndroidPreviewController()
        val provider = Preview.SurfaceProvider { }
        val observed = mutableListOf<Preview.SurfaceProvider?>()
        controller.setVisible(false)
        controller.observeSurfaceProvider(observed::add)

        controller.attachSurfaceProvider(provider)
        controller.setVisible(true)

        assertNull(observed[0])
        assertNull(observed[1])
        assertSame(provider, observed[2])
    }

    @Test
    fun repeatedVisibleCommandDoesNotRestartAHealthyPreviewSurface() {
        val controller = AndroidPreviewController()
        val provider = Preview.SurfaceProvider { }
        val observed = mutableListOf<Preview.SurfaceProvider?>()
        controller.attachSurfaceProvider(provider)
        controller.observeSurfaceProvider(observed::add)

        controller.setVisible(true)

        assertEquals(1, observed.size)
        assertSame(provider, observed.single())
    }
}
