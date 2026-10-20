package com.example.ialocal.ui.branding

import android.content.res.Resources
import android.util.TypedValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ialocal.R
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.ModelCatalog
import com.example.ialocal.models.ModelProvider

fun ModelProvider.brandName(): String = when (this) {
    ModelProvider.PRISMML -> "PrismML / Bonsai"
    ModelProvider.GOOGLE -> "Google"
    ModelProvider.ALIBABA -> "Alibaba / Qwen"
    ModelProvider.META -> "Meta"
    ModelProvider.MISTRAL -> "Mistral AI"
    ModelProvider.DEEPSEEK -> "DeepSeek"
    ModelProvider.MICROSOFT -> "Microsoft"
    ModelProvider.IBM -> "IBM / Granite"
    ModelProvider.LIQUID -> "Liquid AI"
    ModelProvider.AI2 -> "Ai2 / OLMo"
    ModelProvider.NVIDIA -> "NVIDIA"
    ModelProvider.OPENAI -> "OpenAI"
    ModelProvider.OPENBMB -> "OpenBMB / MiniCPM"
    ModelProvider.JETBRAINS -> "JetBrains"
    ModelProvider.ORNITH -> "Ornith AI"
    ModelProvider.INCLUSION_AI -> "inclusionAI / Ling"
    ModelProvider.POWERINFER -> "PowerInfer (Tiiny)"
    ModelProvider.ZAI -> "Z.ai / GLM"
    ModelProvider.COHERE -> "Cohere"
    ModelProvider.POOLSIDE -> "Poolside"
    ModelProvider.SBER -> "Sber / GigaChat"
}

/** Every company has its own logo, shown in the catalog and next to its model's replies in the chat. */
fun ModelProvider.logoRes(): Int = when (this) {
    ModelProvider.GOOGLE -> R.drawable.provider_google
    ModelProvider.ALIBABA -> R.drawable.provider_alibaba
    ModelProvider.META -> R.drawable.provider_meta
    ModelProvider.MISTRAL -> R.drawable.provider_mistral
    ModelProvider.DEEPSEEK -> R.drawable.provider_deepseek
    ModelProvider.MICROSOFT -> R.drawable.provider_microsoft
    ModelProvider.PRISMML -> R.drawable.provider_prismml
    ModelProvider.IBM -> R.drawable.provider_ibm
    ModelProvider.LIQUID -> R.drawable.provider_liquid
    ModelProvider.AI2 -> R.drawable.provider_ai2
    ModelProvider.NVIDIA -> R.drawable.provider_nvidia
    ModelProvider.OPENAI -> R.drawable.provider_openai
    ModelProvider.OPENBMB -> R.drawable.provider_openbmb
    ModelProvider.JETBRAINS -> R.drawable.provider_jetbrains
    ModelProvider.ORNITH -> R.drawable.provider_ornith
    ModelProvider.INCLUSION_AI -> R.drawable.provider_inclusionai
    ModelProvider.POWERINFER -> R.drawable.provider_powerinfer
    ModelProvider.ZAI -> R.drawable.provider_zai
    ModelProvider.COHERE -> R.drawable.provider_cohere
    ModelProvider.POOLSIDE -> R.drawable.provider_poolside
    ModelProvider.SBER -> R.drawable.provider_sber
}

/** The logos drawn as pictures (WebP), decoded once; null for the vector logos. */
private val rasterLogos = HashMap<Int, ImageBitmap?>()

private fun rasterLogo(resources: Resources, id: Int): ImageBitmap? {
    synchronized(rasterLogos) {
        if (id in rasterLogos) return rasterLogos[id]
        val path = TypedValue().also { resources.getValue(id, it, true) }.string
        val bitmap = if (path?.endsWith(".xml") == true) null else ImageBitmap.imageResource(resources, id)
        rasterLogos[id] = bitmap
        return bitmap
    }
}

/**
 * The company's logo. painterResource decodes a WebP logo again in every list item that comes into
 * view, on the main thread, which made the catalog and the chat stutter while scrolling; Compose
 * already keeps the vector ones.
 */
@Composable
fun providerLogoPainter(provider: ModelProvider): Painter {
    val id = provider.logoRes()
    val resources = LocalResources.current
    val bitmap = remember(id, resources) { rasterLogo(resources, id) }
    return if (bitmap != null) remember(bitmap) { BitmapPainter(bitmap) } else painterResource(id)
}

fun AiModelEntity.catalogProvider(): ModelProvider? =
    ModelCatalog.entries.firstOrNull { apiModelId.startsWith(it.apiIdPrefix) }?.provider

@Composable
fun ProviderLogo(
    provider: ModelProvider,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = providerLogoPainter(provider),
                contentDescription = null,
                // Rounded like the frame: most logos are square pictures with their own background.
                // The margin follows the size, so small logos (in chips and filters) stay legible.
                modifier = Modifier.padding(size * LOGO_MARGIN_FRACTION).clip(RoundedCornerShape(5.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Margin around a logo in its frame: 6 dp in the usual 38 dp frame. */
private const val LOGO_MARGIN_FRACTION = 0.16f
