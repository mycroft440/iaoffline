package com.example.ialocal.ads

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ialocal.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Native ads of one screen visit, one per slot. They are kept here rather than in the list items,
 * so scrolling an ad out of view and back does not request a new one; all are destroyed when the
 * screen closes.
 */
class NativeAdSlots(private val context: Context, private val adUnitId: String) {
    val ads = mutableStateMapOf<Int, NativeAd>()
    private val requested = hashSetOf<Int>()
    private var destroyed = false

    fun request(slot: Int) {
        if (destroyed || !requested.add(slot)) return
        AdLoader.Builder(context, adUnitId)
            .forNativeAd { ad ->
                if (destroyed) ad.destroy() else ads[slot] = ad
            }
            .withAdListener(
                object : AdListener() {
                    // Without a connection the slot stays empty; the next visit tries again.
                    override fun onAdFailedToLoad(error: LoadAdError) = Unit
                },
            )
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    fun destroy() {
        destroyed = true
        ads.values.forEach { it.destroy() }
        ads.clear()
    }
}

/** Native ad slots for a screen, or null when ads are off. */
@Composable
fun rememberNativeAdSlots(enabled: Boolean): NativeAdSlots? {
    val adUnitId = BuildConfig.NATIVE_AD_UNIT_ID
    if (!enabled || adUnitId.isBlank()) return null
    // Some mediation networks need the activity to load native ads.
    val context = LocalContext.current
    val slots = remember(context, adUnitId) { NativeAdSlots(context, adUnitId) }
    DisposableEffect(slots) { onDispose { slots.destroy() } }
    return slots
}

/** A native ad laid out like the catalog cards; nothing is shown until the ad arrives. */
@Composable
fun NativeAdCard(slots: NativeAdSlots, slot: Int, modifier: Modifier = Modifier) {
    LaunchedEffect(slots, slot) { slots.request(slot) }
    val ad = slots.ads[slot] ?: return
    AndroidView(
        factory = ::NativeAdCardView,
        update = { it.bind(ad) },
        modifier = modifier,
    )
}

private class NativeAdCardView(context: Context) : FrameLayout(context) {
    private val root = NativeAdView(context)
    private val density = context.resources.displayMetrics.density
    private val icon = ImageView(context)
    private val headline = TextView(context)
    private val body = TextView(context)
    private val media = MediaView(context)
    private val callToAction = Button(context)

    init {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(CARD, dp(16)).apply { setStroke(dp(1), BORDER) }
        }

        val badge = TextView(context).apply {
            text = "Anúncio"
            textSize = 10f
            setTextColor(AMBER)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(AMBER_BACKGROUND, dp(6))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        headline.apply {
            textSize = 15f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(badge, wrap())
            addView(headline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.apply {
            textSize = 12f
            setTextColor(MUTED)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        callToAction.apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(ON_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(CYAN, dp(10))
        }

        content.addView(header, matchWidth())
        content.addView(body, matchWidth().apply { topMargin = dp(10) })
        content.addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)).apply { topMargin = dp(10) })
        content.addView(callToAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        root.iconView = icon
        root.headlineView = headline
        root.bodyView = body
        root.mediaView = media
        root.callToActionView = callToAction
    }

    fun bind(ad: NativeAd) {
        headline.text = ad.headline
        body.text = ad.body
        body.visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
        callToAction.text = ad.callToAction
        callToAction.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        val drawable = ad.icon?.drawable
        icon.setImageDrawable(drawable)
        icon.visibility = if (drawable == null) View.GONE else View.VISIBLE
        val mediaContent = ad.mediaContent
        if (mediaContent != null) media.mediaContent = mediaContent
        media.visibility = if (mediaContent == null) View.GONE else View.VISIBLE
        root.setNativeAd(ad)
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private companion object {
        // The catalog's card, border, text and accent colors.
        const val CARD = 0xFF111726.toInt()
        const val BORDER = 0xFF1F293D.toInt()
        const val TEXT = 0xFFF8FAFC.toInt()
        const val MUTED = 0xFF94A3B8.toInt()
        const val CYAN = 0xFF06B6D4.toInt()
        const val ON_CYAN = 0xFF041014.toInt()
        const val AMBER = 0xFFFBBF24.toInt()
        const val AMBER_BACKGROUND = 0x26FBBF24
    }
}
