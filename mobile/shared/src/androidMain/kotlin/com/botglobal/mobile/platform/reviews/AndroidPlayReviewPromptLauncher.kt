package com.botglobal.mobile.platform.reviews

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManagerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class AndroidPlayReviewPromptLauncher(
    private val activityProvider: () -> Activity?,
) : ReviewPromptLauncher {
    override suspend fun requestReview(beforeNativeLaunch: suspend () -> ReviewPreLaunchDecision): ReviewLaunchOutcome =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityProvider().takeIf(::canLaunchFrom) ?: return@withContext ReviewLaunchOutcome.Deferred
            val manager = ReviewManagerFactory.create(activity.applicationContext)
            val reviewInfo: ReviewInfo = try {
                suspendCancellableCoroutine<ReviewInfo> { continuation ->
                    val task = manager.requestReviewFlow()
                    task.addOnCompleteListener { completed ->
                        if (!continuation.isActive) return@addOnCompleteListener
                        if (completed.isSuccessful) {
                            continuation.resume(completed.result)
                        } else {
                            continuation.resumeWithException(
                                completed.exception ?: IllegalStateException("Play review flow is unavailable."),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext ReviewLaunchOutcome.Failed
            }

            val currentActivity = activityProvider()
            if (currentActivity !== activity || !canLaunchFrom(currentActivity)) {
                return@withContext ReviewLaunchOutcome.Deferred
            }

            try {
                when (beforeNativeLaunch()) {
                    ReviewPreLaunchDecision.Proceed -> Unit
                    ReviewPreLaunchDecision.Deferred -> return@withContext ReviewLaunchOutcome.Deferred
                    ReviewPreLaunchDecision.Failed -> return@withContext ReviewLaunchOutcome.Failed
                }
                suspendCancellableCoroutine<Unit> { continuation ->
                    val task = manager.launchReviewFlow(activity, reviewInfo)
                    task.addOnCompleteListener { completed ->
                        if (!continuation.isActive) return@addOnCompleteListener
                        if (completed.isSuccessful) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(
                                completed.exception ?: IllegalStateException("Play review flow did not finish."),
                            )
                        }
                    }
                }
                ReviewLaunchOutcome.Launched
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ReviewLaunchOutcome.Failed
            }
        }

    private fun canLaunchFrom(activity: Activity?): Boolean =
        activity != null &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            (activity as? LifecycleOwner)
                ?.lifecycle
                ?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) == true
}
