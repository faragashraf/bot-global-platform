package com.ashraffarag.sentricam.live.capability

class AttachableCameraStreamController : CameraStreamController {
    private val lock = Any()
    private var delegate: CameraStreamController? = null
    private var consumer: CameraFrameConsumer? = null
    private var preserveConsumerOnDetach = false

    fun attach(controller: CameraStreamController) {
        synchronized(lock) {
            delegate?.let { current -> if (consumer != null) current.stop() }
            delegate = controller
            consumer?.let { check(controller.start(it)) { "live_stream_replacement_failed" } }
        }
    }

    fun detach() {
        synchronized(lock) {
            if (consumer != null) delegate?.stop()
            delegate = null
            if (!preserveConsumerOnDetach) consumer = null
            preserveConsumerOnDetach = false
        }
    }

    fun preserveConsumerForReplacement() = synchronized(lock) {
        preserveConsumerOnDetach = consumer != null
    }

    fun isAttached(): Boolean = synchronized(lock) { delegate != null }
    fun isActive(): Boolean = synchronized(lock) { consumer != null }

    override fun start(consumer: CameraFrameConsumer): Boolean = synchronized(lock) {
        if (this.consumer != null) return@synchronized false
        val current = delegate ?: return@synchronized false
        if (!current.start(consumer)) return@synchronized false
        this.consumer = consumer
        true
    }

    override fun stop() {
        synchronized(lock) {
            if (consumer != null) delegate?.stop()
            consumer = null
            preserveConsumerOnDetach = false
        }
    }
}
