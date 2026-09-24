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
import androidx.compose.runtime.LaunchedEffect
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
 * An AdMob banner. [anchored] uses the short anchored adaptive size meant for a banner pinned to the
 * bottom of the screen; otherwise the taller inline adaptive size used between content.
 */
@Composable
fun InlineAdBanner(enabled: Boolean, modifier: Modifier = Modifier, anchored: Boolean = false) {
    val adUnitId = BuildConfig.BANNER_AD_UNIT_ID
    if (!enabled || adUnitId.isBlank()) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // A failed load (usually no connection) hides the banner; it is retried when a network appears.
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (failed) {
                        failed = false
                        attempt += 1
                    }
                }
            }
        }
        val registered = connectivity != null && runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
        }.isSuccess
        onDispose {
            if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        key(adUnitId, widthDp, attempt) {
            var loaded by remember { mutableStateOf(false) }
            if (!failed) {
                val adView = remember {
                    AdView(context).apply {
                        this.adUnitId = adUnitId
                        setAdSize(
                            if (anchored) {
                                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
                            } else {
                                AdSize.getInlineAdaptiveBannerAdSize(widthDp, 120)
                            }
                        )
                        adListener = object : AdListener() {
                            override fun onAdLoaded() {
                                loaded = true
                            }

                            override fun onAdFailedToLoad(error: LoadAdError) {
                                failed = true
                            }
                        }
                    }
                }

                DisposableEffect(lifecycleOwner, adView) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> adView.pause()
                            Lifecycle.Event.ON_RESUME -> adView.resume()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        (adView.parent as? ViewGroup)?.removeView(adView)
                        adView.destroy()
                    }
                }

                LaunchedEffect(adView) { adView.loadAd(AdRequest.Builder().build()) }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = if (!loaded) 0.dp else if (anchored) 6.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (anchored) 4.dp else 6.dp),
                ) {
                    if (loaded) Text("Publicidade", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    AndroidView(
                        factory = { adView },
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (loaded) 50.dp else 0.dp),
                    )
                }
            }
        }
    }
}
