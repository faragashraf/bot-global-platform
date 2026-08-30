package com.botglobal.mobile.platform.phoneidentity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState

class AndroidPhoneIdentityDiscovery(
    context: Context,
    private val permissions: PermissionController,
) : PhoneIdentityDiscovery {
    private val applicationContext = context.applicationContext

    override suspend fun discoverAfterExplanation(): PhoneIdentityDiscoveryResult {
        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)) {
            return PhoneIdentityDiscoveryResult.Unavailable(
                PhoneIdentityDiscoveryUnavailableReason.UnsupportedDevice,
            )
        }

        val permission = when (val current = permissions.state(PermissionKind.PhoneNumberDiscovery)) {
            PermissionState.Granted -> current
            PermissionState.Unavailable -> return unavailable(PhoneIdentityDiscoveryUnavailableReason.PermissionUnavailable)
            PermissionState.PermanentlyDenied -> return unavailable(
                PhoneIdentityDiscoveryUnavailableReason.PermissionPermanentlyDenied,
            )
            PermissionState.Unknown,
            PermissionState.Denied,
            -> permissions.requestAfterExplanation(PermissionKind.PhoneNumberDiscovery)
        }
        if (permission != PermissionState.Granted) {
            val reason = if (permission == PermissionState.PermanentlyDenied) {
                PhoneIdentityDiscoveryUnavailableReason.PermissionPermanentlyDenied
            } else {
                PhoneIdentityDiscoveryUnavailableReason.PermissionDenied
            }
            return unavailable(reason)
        }

        return runCatching {
            val manager = applicationContext.getSystemService(SubscriptionManager::class.java)
                ?: return@runCatching PhoneIdentityDiscoveryResult.Unavailable(
                    PhoneIdentityDiscoveryUnavailableReason.UnsupportedDevice,
                )
            val candidates = manager.activeSubscriptionInfoList.orEmpty()
                .mapNotNull { subscription -> subscription.toCandidate(manager) }
                .distinctBy { it.number.canonical }
            if (candidates.isEmpty()) PhoneIdentityDiscoveryResult.NoCandidates
            else PhoneIdentityDiscoveryResult.Candidates(candidates)
        }.getOrElse { cause ->
            when (cause) {
                is SecurityException -> unavailable(PhoneIdentityDiscoveryUnavailableReason.PermissionDenied)
                else -> unavailable(PhoneIdentityDiscoveryUnavailableReason.PlatformFailure)
            }
        }
    }

    private fun SubscriptionInfo.toCandidate(manager: SubscriptionManager): PhoneIdentityCandidate? {
        val rawNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPhoneNumber(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            number
        }
        val number = E164PhoneNumber.parse(rawNumber.orEmpty()) ?: return null
        return PhoneIdentityCandidate(
            number = number,
            source = if (isEmbedded) PhoneIdentitySource.Esim else PhoneIdentitySource.Sim,
            lineLabel = displayName?.toString()?.trim()?.takeIf(String::isNotEmpty),
            carrierLabel = carrierName?.toString()?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun unavailable(reason: PhoneIdentityDiscoveryUnavailableReason) =
        PhoneIdentityDiscoveryResult.Unavailable(reason)
}
