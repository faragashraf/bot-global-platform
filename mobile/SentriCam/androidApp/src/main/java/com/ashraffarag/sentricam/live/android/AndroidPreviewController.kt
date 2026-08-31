package com.ashraffarag.sentricam.live.android

import com.ashraffarag.sentricam.live.capability.PreviewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.camera.core.Preview

class AndroidPreviewController : PreviewController {
    private val mutableVisible = MutableStateFlow(true)
    override val visible = mutableVisible.asStateFlow()
    private val mutablePresentation = MutableStateFlow("visible")
    val presentation = mutablePresentation.asStateFlow()

    @Synchronized
    override fun setVisible(visible: Boolean) {
        setPresentation(if (visible) "visible" else "hidden")
    }

    @Synchronized
    fun setPresentation(mode: String) {
        val normalized = mode.takeIf { it == "visible" || it == "hidden" || it == "dimmed" } ?: "visible"
        val visible = normalized != "hidden"
        LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
            "event=visibility previous=${mutablePresentation.value} current=$normalized provider=${surfaceProvider.id()} listeners=${listeners.size}"
        }
        if (mutablePresentation.value == normalized) return
        mutablePresentation.value = normalized
        mutableVisible.value = visible
        // PreviewView can cancel its TextureView surface request while INVISIBLE. Explicitly
        // withdraw and reissue the provider so Hidden -> Visible never inherits a dead surface.
        val activeProvider = surfaceProvider.takeIf { visible }
        listeners.toList().forEach { it(activeProvider) }
    }

    private val listeners = mutableSetOf<(Preview.SurfaceProvider?) -> Unit>()
    private var surfaceProvider: Preview.SurfaceProvider? = null

    @Synchronized
    fun attachSurfaceProvider(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
            "event=surface_attach provider=${provider.id()} visible=${mutableVisible.value} listeners=${listeners.size}"
        }
        listeners.toList().forEach { it(provider.takeIf { mutableVisible.value }) }
    }

    @Synchronized
    fun detachSurfaceProvider(provider: Preview.SurfaceProvider) {
        if (surfaceProvider !== provider) return
        surfaceProvider = null
        LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
            "event=surface_detach provider=${provider.id()} visible=${mutableVisible.value} listeners=${listeners.size}"
        }
        listeners.toList().forEach { it(null) }
    }

    @Synchronized
    fun observeSurfaceProvider(listener: (Preview.SurfaceProvider?) -> Unit): AutoCloseable {
        listeners += listener
        LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
            "event=surface_observe provider=${surfaceProvider.id()} visible=${mutableVisible.value} listeners=${listeners.size}"
        }
        listener(surfaceProvider.takeIf { mutableVisible.value })
        return AutoCloseable {
            synchronized(this) {
                listeners -= listener
                LiveViewDiagnostics.log(LiveViewDiagnostics.PREVIEW) {
                    "event=surface_observer_closed provider=${surfaceProvider.id()} listeners=${listeners.size}"
                }
            }
        }
    }

    private fun Any?.id(): String = this?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "null"
}
