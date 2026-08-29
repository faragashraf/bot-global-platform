package com.botglobal.lamma

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.botglobal.lamma.app.platform.AndroidApplicationLanguagePreferences
import com.botglobal.lamma.app.platform.AndroidSecureSessionVault
import com.botglobal.lamma.app.platform.AndroidSemanticHaptics
import com.botglobal.lamma.app.ui.FamilyGamesApp
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.invitations.InvitationShareContent
import com.botglobal.mobile.platform.invitations.PlatformShareCapability
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : FragmentActivity() {
    private val foregroundEvents = MutableSharedFlow<Unit>(replay = 1)
    private val invitationLinks = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    private var cameraPermissionContinuation: CancellableContinuation<PermissionState>? = null
    private var qrScanContinuation: CancellableContinuation<QrScanResult>? = null
    private var cameraPermissionRequested = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val result = if (granted) {
            PermissionState.Granted
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            PermissionState.PermanentlyDenied
        } else {
            PermissionState.Denied
        }
        cameraPermissionContinuation?.takeIf { it.isActive }?.resume(result)
        cameraPermissionContinuation = null
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val outcome = result.contents?.takeIf { it.isNotBlank() }?.let(QrScanResult::Recognized)
            ?: QrScanResult.Cancelled
        qrScanContinuation?.takeIf { it.isActive }?.resume(outcome)
        qrScanContinuation = null
    }

    private val permissionController = object : PermissionController {
        override suspend fun state(permission: PermissionKind): PermissionState {
            if (permission != PermissionKind.Camera) return PermissionState.Unavailable
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                return PermissionState.Granted
            }
            return if (cameraPermissionRequested &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, Manifest.permission.CAMERA)
            ) {
                PermissionState.PermanentlyDenied
            } else {
                PermissionState.Unknown
            }
        }

        override suspend fun requestAfterExplanation(permission: PermissionKind): PermissionState {
            if (permission != PermissionKind.Camera) return PermissionState.Unavailable
            return suspendCancellableCoroutine { continuation ->
                cameraPermissionRequested = true
                cameraPermissionContinuation?.cancel()
                cameraPermissionContinuation = continuation
                continuation.invokeOnCancellation { cameraPermissionContinuation = null }
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private val qrScanner = object : QrScannerCapability {
        override suspend fun scan(prompt: String): QrScanResult = suspendCancellableCoroutine { continuation ->
            qrScanContinuation?.cancel()
            qrScanContinuation = continuation
            continuation.invokeOnCancellation { qrScanContinuation = null }
            qrScanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(prompt)
                    .setBeepEnabled(false)
                    .setOrientationLocked(true),
            )
        }
    }

    private val nativeShare = object : PlatformShareCapability {
        override fun share(content: InvitationShareContent): Boolean = runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, content.title)
                        putExtra(Intent.EXTRA_TEXT, content.message)
                    },
                    getString(R.string.share_invitation_chooser),
                ),
            )
        }.isSuccess
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = AndroidSecureSessionVault(applicationContext)
        val languagePreferences = AndroidApplicationLanguagePreferences(applicationContext)
        val haptics = AndroidSemanticHaptics(applicationContext)
        val networkAvailability = AndroidNetworkAvailability(applicationContext)
        setContent {
            FamilyGamesApp(
                apiBaseUrl = BuildConfig.API_BASE_URL,
                sessionVault = vault,
                haptics = haptics,
                appVersion = BuildConfig.VERSION_NAME,
                platform = "android",
                openExternalUrl = { destination ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
                },
                foregroundEvents = foregroundEvents,
                invitationLinks = invitationLinks,
                invitationLinkBase = BuildConfig.INVITATION_LINK_BASE,
                platformShare = nativeShare,
                qrScanner = qrScanner,
                permissions = permissionController,
                networkAvailability = networkAvailability,
                languagePreferences = languagePreferences,
                invitationQr = { content, description, modifier ->
                    AndroidInvitationQr(content, description, modifier)
                },
            )
        }
        processInvitation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processInvitation(intent)
    }

    override fun onStart() {
        super.onStart()
        foregroundEvents.tryEmit(Unit)
    }

    private fun processInvitation(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let(invitationLinks::tryEmit)
        }
    }
}
