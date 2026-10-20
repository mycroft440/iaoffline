package com.example.ialocal.ads

import android.app.Activity
import android.content.Context
import com.example.ialocal.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.lang.ref.WeakReference

/**
 * An opt-in rewarded ad. Only the earned-reward callback authorizes a download.
 * No inventory invokes the previous free-download fallback; skipping never grants a reward.
 * Called exclusively from the main thread.
 */
class DownloadRewarded(appContext: Context) {
    private val context = appContext.applicationContext
    private var ad: RewardedAd? = null
    private var loadedAtMs = 0L
    private var loading = false
    private var showing = false
    private var pending: Pending? = null

    val isShowing: Boolean get() = showing
    var lastShownAtMs: Long = 0L
        private set

    private data class Pending(
        val activity: WeakReference<Activity>,
        val onReward: () -> Unit,
        val onUnavailable: () -> Unit,
    )

    fun preload() {
        val unitId = BuildConfig.REWARDED_AD_UNIT_ID
        if (unitId.isBlank() || loading || showing) return
        if (ad != null && DownloadAdPacing.isFresh(System.currentTimeMillis(), loadedAtMs)) return

        ad = null
        loading = true
        RewardedAd.load(context, unitId, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(loaded: RewardedAd) {
                loading = false
                ad = loaded
                loadedAtMs = System.currentTimeMillis()
                pending?.let { request ->
                    pending = null
                    val activity = request.activity.get()
                    if (activity == null || activity.isFinishing || activity.isDestroyed) return@let
                    present(activity, request.onReward, request.onUnavailable)
                }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loading = false
                ad = null
                pending?.let { request ->
                    pending = null
                    if (request.activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true) {
                        request.onUnavailable()
                    }
                }
            }
        })
    }

    fun show(
        activity: Activity,
        onReward: () -> Unit,
        onUnavailable: () -> Unit,
        onBusy: () -> Unit,
    ) {
        if (showing || pending != null) {
            onBusy()
            return
        }
        if (BuildConfig.REWARDED_AD_UNIT_ID.isBlank()) {
            onUnavailable()
            return
        }
        val fresh = ad?.takeIf { DownloadAdPacing.isFresh(System.currentTimeMillis(), loadedAtMs) }
        if (fresh != null) {
            present(activity, onReward, onUnavailable)
        } else {
            pending = Pending(WeakReference(activity), onReward, onUnavailable)
            preload()
        }
    }

    private fun present(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) {
        val ready = ad ?: run { onUnavailable(); return }
        ad = null
        showing = true
        val grant = RewardedDownloadGrant(onReward)
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastShownAtMs = System.currentTimeMillis()
            }

            override fun onAdDismissedFullScreenContent() {
                showing = false
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                if (!grant.granted) onUnavailable()
                preload()
            }
        }
        ready.show(activity) { grant.earn() }
    }
}

/** Prevents duplicate SDK callbacks from authorizing multiple downloads. */
internal class RewardedDownloadGrant(private val onReward: () -> Unit) {
    var granted: Boolean = false
        private set

    fun earn() {
        if (granted) return
        granted = true
        onReward()
    }
}
