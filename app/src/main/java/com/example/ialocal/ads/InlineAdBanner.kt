package com.example.ialocal.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ialocal.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * A banner's ad for as long as its screen is open. Remembered above a lazy list with
 * [rememberInlineAdBannerState], a banner that scrolls out of view and back is the same ad: before,
 * every return built another AdView (a WebView, slow to create on the main thread) and loaded
 * another ad, so the list stalled and then jumped when the new ad came in.
 */
@Stable
class InlineAdBannerState internal constructor(
    private val context: Context,
    private val adUnitId: String,
    internal val anchored: Boolean,
) {
    /**
     * Set when the screen has finished opening: built during the screen transition, the WebView
     * made the transition stutter.
     */
    internal var started by mutableStateOf(false)
        private set

    /** A failed load (usually no connection) hides the banner; it is retried when a network appears. */
    internal var failed by mutableStateOf(false)
        private set

    internal var attempt by mutableIntStateOf(0)
        private set

    /** The view whose ad arrived; the banner takes no space before. */
    internal var loadedView by mutableStateOf<AdView?>(null)
        private set

    private var view: AdView? = null
    private var adViewWidthDp = 0
    private var adViewAttempt = -1
    private var paused = false

    /** The banner's view for [widthDp], built and loaded the first time. */
    internal fun adView(widthDp: Int): AdView {
        view?.let { if (adViewWidthDp == widthDp && adViewAttempt == attempt) return it }
        destroyAdView()
        val adView = AdView(context)
        adView.adUnitId = adUnitId
        adView.setAdSize(
            if (anchored) {
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
            } else {
                AdSize.getInlineAdaptiveBannerAdSize(widthDp, 120)
            },
        )
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                if (view === adView) loadedView = adView
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                if (view === adView) failed = true
            }
        }
        view = adView
        adViewWidthDp = widthDp
        adViewAttempt = attempt
        adView.loadAd(AdRequest.Builder().build())
        if (paused) adView.pause()
        return adView
    }

    internal fun start() {
        started = true
    }

    internal fun retry() {
        if (!failed) return
        failed = false
        attempt += 1
    }

    internal fun pause() {
        paused = true
        view?.pause()
    }

    internal fun resume() {
        paused = false
        view?.resume()
    }

    internal fun destroy() {
        destroyAdView()
    }

    private fun destroyAdView() {
        val adView = view ?: return
        view = null
        loadedView = null
        (adView.parent as? ViewGroup)?.removeView(adView)
        adView.destroy()
    }
}

/**
 * The state of a banner kept for the whole visit to a screen, or null when ads are off. [anchored]
 * uses the short anchored adaptive size meant for a banner pinned to the bottom of the screen;
 * otherwise the taller inline adaptive size used between content.
 */
@Composable
fun rememberInlineAdBannerState(enabled: Boolean, anchored: Boolean = false): InlineAdBannerState? {
    val adUnitId = BuildConfig.BANNER_AD_UNIT_ID
    if (!enabled || adUnitId.isBlank()) return null

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(context, adUnitId, anchored) { InlineAdBannerState(context, adUnitId, anchored) }

    // A screen of the navigation is resumed only once its opening transition has ended.
    DisposableEffect(state, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> state.pause()
                Lifecycle.Event.ON_RESUME -> {
                    state.start()
                    state.resume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(state) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { state.retry() }
            }
        }
        val registered = connectivity != null && runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
        }.isSuccess
        onDispose {
            if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
            state.destroy()
        }
    }
    return state
}

/** An AdMob banner of its own, gone with the composable; see [rememberInlineAdBannerState]. */
@Composable
fun InlineAdBanner(enabled: Boolean, modifier: Modifier = Modifier, anchored: Boolean = false) {
    InlineAdBanner(state = rememberInlineAdBannerState(enabled, anchored), modifier = modifier)
}

/** The banner of [state]; nothing, and no space, until its ad arrives. */
@Composable
fun InlineAdBanner(state: InlineAdBannerState?, modifier: Modifier = Modifier) {
    if (state == null || !state.started || state.failed) return

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        val adView = remember(state, widthDp, state.attempt) { state.adView(widthDp) }
        val loaded = state.loadedView === adView
        val anchored = state.anchored

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = if (!loaded) 0.dp else if (anchored) 6.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (anchored) 4.dp else 6.dp),
        ) {
            if (loaded) Text("Publicidade", color = Color(0xFF94A3B8), fontSize = 10.sp)
            key(adView) {
                AndroidView(
                    // Back in view after its list item was disposed, the view still sits in the old item.
                    factory = { adView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (loaded) 50.dp else 0.dp),
                )
            }
        }
    }
}
