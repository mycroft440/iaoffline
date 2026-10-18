package com.example.ialocal.ads

import android.app.Activity
import android.content.Context
import com.example.ialocal.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * The full-screen ad shown when the user comes back to the app, paced by [AppOpenAdPacing]. It is
 * loaded only when the next return could show it, so few loaded ads go unused. Main thread only.
 */
class AppOpenAds(appContext: Context) {
    private val context = appContext.applicationContext
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var ad: AppOpenAd? = null
    private var loadedAtMs = 0L
    private var loading = false

    private val openings: Int get() = preferences.getInt(KEY_OPENINGS, 0)
    private val lastShownAtMs: Long get() = preferences.getLong(KEY_LAST_SHOWN_AT, 0L)

    fun preload() {
        val adUnitId = BuildConfig.APP_OPEN_AD_UNIT_ID
        if (adUnitId.isBlank() || loading) return
        val now = System.currentTimeMillis()
        if (ad != null && AppOpenAdPacing.isFresh(now, loadedAtMs)) return
        ad = null
        if (!AppOpenAdPacing.worthLoading(now, openings + 1, lastShownAtMs)) return
        loading = true
        AppOpenAd.load(
            context,
            adUnitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(loaded: AppOpenAd) {
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

    /** Counts a return to the app; the first openings never show the ad. */
    fun countOpening() {
        preferences.edit().putInt(KEY_OPENINGS, openings + 1).apply()
    }

    /** Shows the ad if one is ready and pacing allows it; otherwise loads one for a later return. */
    fun show(activity: Activity, lastDownloadAdAtMs: Long) {
        val now = System.currentTimeMillis()
        val ready = ad?.takeIf { AppOpenAdPacing.isFresh(now, loadedAtMs) }
        if (ready == null || !AppOpenAdPacing.canShow(now, openings, lastShownAtMs, lastDownloadAdAtMs)) {
            preload()
            return
        }
        ad = null
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                preferences.edit().putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis()).apply()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                preload()
            }
        }
        ready.show(activity)
    }

    private companion object {
        const val PREFS_NAME = "app_open_ad"
        const val KEY_OPENINGS = "openings"
        const val KEY_LAST_SHOWN_AT = "last_shown_at"
    }
}
