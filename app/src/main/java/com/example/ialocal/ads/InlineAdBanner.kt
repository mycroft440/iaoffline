package com.example.ialocal.ads

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

@Composable
fun InlineAdBanner(enabled: Boolean, modifier: Modifier = Modifier) {
    val adUnitId = BuildConfig.BANNER_AD_UNIT_ID
    if (!enabled || adUnitId.isBlank()) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        key(adUnitId, widthDp) {
            var failed by remember { mutableStateOf(false) }
            if (!failed) {
                val adView = remember {
                    AdView(context).apply {
                        this.adUnitId = adUnitId
                        setAdSize(AdSize.getInlineAdaptiveBannerAdSize(widthDp, 120))
                        adListener = object : AdListener() {
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Publicidade", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    AndroidView(
                        factory = { adView },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    )
                }
            }
        }
    }
}
