package com.example.ialocal.ui.branding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
                painter = painterResource(provider.logoRes()),
                contentDescription = null,
                // Rounded like the frame: most logos are square pictures with their own background.
                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(5.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
