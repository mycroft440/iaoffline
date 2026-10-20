package com.example.ialocal.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ialocal.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class AdsManager(private val appContext: Context) {
    var adsReady by mutableStateOf(false)
        private set

    var privacyOptionsRequired by mutableStateOf(false)
        private set

    private val consentInformation by lazy { UserMessagingPlatform.getConsentInformation(appContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadInterstitial = DownloadInterstitial(appContext)
    private val downloadRewarded = DownloadRewarded(appContext)
    private val sectionEntryPrefs = appContext.getSharedPreferences("rewarded_section_entry", Context.MODE_PRIVATE)
    private val appOpenAds = AppOpenAds(appContext)
    private var initializing = false
    private var initialized = false

    fun start(activity: Activity) {
        if (BuildConfig.BANNER_AD_UNIT_ID.isBlank()) return

        // Google's test ads need no consent form: there is no AdMob privacy message for test IDs.
        if (BuildConfig.TEST_ADS) {
            initializeAds()
            return
        }

        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                updatePrivacyOptions()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updatePrivacyOptions()
                    updateAdsAccess()
                }
            },
            {
                updatePrivacyOptions()
                updateAdsAccess()
            },
        )
    }

    /** Loads the download ad ahead of time, when the catalog opens. */
    fun preloadDownloadAd() {
        if (adsReady) {
            downloadInterstitial.preload()
            downloadRewarded.preload()
        }
    }

    /**
     * On entering Wikipedia, the compiler list or profile settings, preload at most once per
     * thirty minutes across all three screens. This does NOT show an ad. An explicit user action
     * can still load a rewarded ad on demand, regardless of this entry-only cooldown.
     */
    fun preloadRewardedOnSectionEntry() {
        if (!adsReady) return
        val now = System.currentTimeMillis()
        val last = sectionEntryPrefs.getLong("last_preload_at", 0L)
        if (!RewardedEntryPacing.canPreload(now, last)) return
        sectionEntryPrefs.edit().putLong("last_preload_at", now).apply()
        downloadRewarded.preload()
    }

    /** Full-screen interstitial immediately after the free IA download begins. */
    fun showDownloadAd(activity: Activity) {
        if (adsReady) downloadInterstitial.show(activity)
    }

    /**
     * Catalog downloads: grant only from the SDK reward callback. If no ad is available,
     * preserve free download; dismissing a shown ad early does not grant anything.
     */
    fun requestRewardedDownload(
        activity: Activity,
        onReward: () -> Unit,
        onUnavailable: () -> Unit,
        onBusy: () -> Unit,
    ) {
        if (!adsReady || BuildConfig.REWARDED_AD_UNIT_ID.isBlank()) {
            onUnavailable()
            return
        }
        downloadRewarded.show(activity, onReward, onUnavailable, onBusy)
    }

    /**
     * The user came back to the app. [aiIdle] is false while an AI is loaded, loading or answering:
     * the ad's video player would then take RAM the model needs.
     */
    fun onAppForeground(activity: Activity, aiIdle: Boolean) {
        appOpenAds.countOpening()
        if (!adsReady || downloadRewarded.isShowing) return
        val lastDownloadAdAt = maxOf(downloadInterstitial.lastShownAtMs, downloadRewarded.lastShownAtMs)
        if (aiIdle) appOpenAds.show(activity, lastDownloadAdAt) else appOpenAds.preload()
    }

    fun showPrivacyOptions(activity: Activity) {
        if (!privacyOptionsRequired) return
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            updatePrivacyOptions()
            updateAdsAccess()
        }
    }

    private fun updatePrivacyOptions() {
        privacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun updateAdsAccess() {
        if (!consentInformation.canRequestAds()) {
            adsReady = false
            return
        }
        initializeAds()
    }

    private fun initializeAds() {
        if (initialized) {
            refreshAdsReady()
            return
        }
        if (initializing) return
        initializing = true
        Thread(
            {
                runCatching {
                    MobileAds.initialize(appContext) {
                        mainHandler.post {
                            initializing = false
                            initialized = true
                            refreshAdsReady()
                        }
                    }
                }.onFailure {
                    mainHandler.post { initializing = false }
                }
            },
            "ia-offline-admob-init",
        ).start()
    }

    private fun refreshAdsReady() {
        adsReady = BuildConfig.TEST_ADS || consentInformation.canRequestAds()
        if (adsReady) {
            appOpenAds.preload()
            downloadRewarded.preload()
        }
    }
}
