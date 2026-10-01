package com.ashraffarag.sentricam.monitoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOwnershipCoordinatorTest {
    @Test
    fun activityAndServiceCanNeverOwnCameraAtTheSameTime() {
        val coordinator = CameraOwnershipCoordinator()

        assertEquals(CameraOwnershipResult.Acquired, coordinator.acquire(CameraOwner.CAMERA_ACTIVITY))
        assertEquals(
            CameraOwnershipResult.Rejected(CameraOwner.CAMERA_ACTIVITY),
            coordinator.acquire(CameraOwner.MONITORING_SERVICE),
        )
        assertFalse(coordinator.release(CameraOwner.MONITORING_SERVICE))
        assertTrue(coordinator.release(CameraOwner.CAMERA_ACTIVITY))
        assertEquals(CameraOwnershipResult.Acquired, coordinator.acquire(CameraOwner.MONITORING_SERVICE))
        assertEquals(CameraOwner.MONITORING_SERVICE, coordinator.owner.value)
    }

    @Test
    fun repeatedAcquireBySameOwnerIsIdempotent() {
        val coordinator = CameraOwnershipCoordinator()
        coordinator.acquire(CameraOwner.MONITORING_SERVICE)

        assertEquals(
            CameraOwnershipResult.AlreadyOwned,
            coordinator.acquire(CameraOwner.MONITORING_SERVICE),
        )
    }

    @Test
    fun twoActivityInstancesDoNotShareOneCameraLease() {
        val coordinator = CameraOwnershipCoordinator()
        assertEquals(
            CameraOwnershipResult.Acquired,
            coordinator.acquire(CameraOwner.CAMERA_ACTIVITY, "activity-one"),
        )

        assertEquals(
            CameraOwnershipResult.Rejected(CameraOwner.CAMERA_ACTIVITY),
            coordinator.acquire(CameraOwner.CAMERA_ACTIVITY, "activity-two"),
        )
        assertFalse(coordinator.release(CameraOwner.CAMERA_ACTIVITY, "activity-two"))
        assertTrue(coordinator.release(CameraOwner.CAMERA_ACTIVITY, "activity-one"))
    }
}
