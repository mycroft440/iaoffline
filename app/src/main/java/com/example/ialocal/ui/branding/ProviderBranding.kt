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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ialocal.R
import com.example.ialocal.data.AiModelEntity
import com.example.ialocal.models.ModelCatalog
import com.example.ialocal.models.ModelProvider

fun ModelProvider.brandName(): String = when (this) {
    ModelProvider.GOOGLE -> "Google"
    ModelProvider.ALIBABA -> "Alibaba / Qwen"
    ModelProvider.META -> "Meta"
    ModelProvider.MISTRAL -> "Mistral AI"
    ModelProvider.DEEPSEEK -> "DeepSeek"
    ModelProvider.MICROSOFT -> "Microsoft"
}

fun ModelProvider.logoRes(): Int = when (this) {
    ModelProvider.GOOGLE -> R.drawable.provider_google
    ModelProvider.ALIBABA -> R.drawable.provider_alibaba
    ModelProvider.META -> R.drawable.provider_meta
    ModelProvider.MISTRAL -> R.drawable.provider_mistral
    ModelProvider.DEEPSEEK -> R.drawable.provider_deepseek
    ModelProvider.MICROSOFT -> R.drawable.provider_microsoft
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
                contentDescription = "Logo ${provider.brandName()}",
                modifier = Modifier.padding(6.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
