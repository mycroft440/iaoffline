package com.example.ialocal.ads

import android.app.Activity
import android.content.Context
import com.example.ialocal.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * The full-screen ad (video or image, whichever wins the auction) shown when the user starts
 * downloading an AI. The download keeps running in the background while it plays, so it costs the
 * user no time. It is loaded when the catalog opens, so it appears at once. Main thread only.
 */
class DownloadInterstitial(appContext: Context) {
    private val context = appContext.applicationContext
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var ad: InterstitialAd? = null
    private var loadedAtMs = 0L
    private var loading = false

    val lastShownAtMs: Long get() = preferences.getLong(KEY_LAST_SHOWN_AT, 0L)

    fun preload() {
        val adUnitId = BuildConfig.INTERSTITIAL_AD_UNIT_ID
        if (adUnitId.isBlank() || loading) return
        if (ad != null && DownloadAdPacing.isFresh(System.currentTimeMillis(), loadedAtMs)) return
        ad = null
        loading = true
        InterstitialAd.load(
            context,
            adUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: InterstitialAd) {
                    loading = false
                    ad = loaded
                    loadedAtMs = System.currentTimeMillis()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                }
            },
        )
    }

    /** Shows the ad unless one was shown in the last few minutes; without a ready ad, loads one for next time. */
    fun show(activity: Activity) {
        val now = System.currentTimeMillis()
        if (!DownloadAdPacing.canShow(now, lastShownAtMs)) return
        val ready = ad?.takeIf { DownloadAdPacing.isFresh(now, loadedAtMs) }
        if (ready == null) {
            preload()
            return
        }
        ad = null
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                preferences.edit().putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis()).apply()
            }

            override fun onAdDismissedFullScreenContent() {
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                preload()
            }
        }
        ready.show(activity)
    }

    private companion object {
        const val PREFS_NAME = "download_interstitial"
        const val KEY_LAST_SHOWN_AT = "last_shown_at"
    }
}
