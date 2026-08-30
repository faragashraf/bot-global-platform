package com.botglobal.mobile.platform.device

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidRuntimePermissionController(
    private val activity: ComponentActivity,
    private val permissions: Map<PermissionKind, List<String>>,
) : PermissionController {
    private var pending: CancellableContinuation<PermissionState>? = null
    private var pendingKind: PermissionKind? = null
    private val requested = mutableSetOf<PermissionKind>()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val kind = pendingKind
        val state = when {
            results.isNotEmpty() && results.values.all { it } -> PermissionState.Granted
            kind == null -> PermissionState.Denied
            permissionNames(kind).any(activity::shouldShowRequestPermissionRationale) -> PermissionState.Denied
            else -> PermissionState.PermanentlyDenied
        }
        pending?.takeIf { it.isActive }?.resume(state)
        pending = null
        pendingKind = null
    }

    override suspend fun state(permission: PermissionKind): PermissionState {
        val names = permissionNames(permission)
        if (names.isEmpty()) return PermissionState.Unavailable
        if (names.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            return PermissionState.Granted
        }
        return if (
            permission in requested &&
            names.none(activity::shouldShowRequestPermissionRationale)
        ) {
            PermissionState.PermanentlyDenied
        } else {
            PermissionState.Unknown
        }
    }

    override suspend fun requestAfterExplanation(permission: PermissionKind): PermissionState {
        val names = permissionNames(permission)
        if (names.isEmpty()) return PermissionState.Unavailable
        if (state(permission) == PermissionState.Granted) return PermissionState.Granted
        return suspendCancellableCoroutine { continuation ->
            pending?.cancel()
            pending = continuation
            pendingKind = permission
            requested += permission
            continuation.invokeOnCancellation {
                if (pending === continuation) {
                    pending = null
                    pendingKind = null
                }
            }
            launcher.launch(names.toTypedArray())
        }
    }

    private fun permissionNames(permission: PermissionKind): List<String> =
        permissions[permission].orEmpty().distinct()
}
