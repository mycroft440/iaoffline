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
    private var initializing = false
    private var initialized = false

    fun start(activity: Activity) {
        if (BuildConfig.BANNER_AD_UNIT_ID.isBlank()) return

        if (BuildConfig.DEBUG) {
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
            { updateAdsAccess() },
        )
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
            adsReady = BuildConfig.DEBUG || consentInformation.canRequestAds()
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
                            adsReady = BuildConfig.DEBUG || consentInformation.canRequestAds()
                        }
                    }
                }.onFailure {
                    mainHandler.post { initializing = false }
                }
            },
            "ia-offline-admob-init",
        ).start()
    }
}
