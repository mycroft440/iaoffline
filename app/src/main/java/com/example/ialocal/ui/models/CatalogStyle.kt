package com.example.ialocal.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ialocal.models.ModelProvider

// The look of "Baixar I.As offline", shared with the home screen.

internal val CatalogBackground = Color(0xFF0A0D14)
internal val CatalogCard = Color(0xFF111726)
internal val CatalogCardDark = Color(0xFF080C14)
internal val CatalogSurface = Color(0xFF0F172A)
internal val CatalogBorder = Color(0xFF1F293D)
internal val CatalogBorderLight = Color(0xFF334155)
internal val CatalogText = Color(0xFFF8FAFC)
internal val CatalogMuted = Color(0xFF94A3B8)
internal val CatalogMutedDark = Color(0xFF64748B)
internal val CatalogCyan = Color(0xFF06B6D4)
internal val CatalogCyanLight = Color(0xFF67E8F9)
internal val CatalogEmerald = Color(0xFF34D399)
internal val CatalogAmber = Color(0xFFFBBF24)
internal val CatalogRose = Color(0xFFFB7185)
/** Mixture-of-experts models: their section, headers and tags. */
internal val CatalogViolet = Color(0xFFA78BFA)

internal data class ProviderUiMeta(
    val selectorName: String,
    val selectorSubtitle: String,
    val sectionName: String,
    val badge: String,
    val tagline: String,
    val accent: Color,
)

@Composable
internal fun SpecCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = CatalogMutedDark,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun CatalogPill(
    text: String,
    foreground: Color,
    background: Color,
    border: Color,
    leadingDot: Boolean = false,
) {
    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(7.dp))
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(foreground, RoundedCornerShape(50)),
            )
        }
        Text(
            text = text,
            color = foreground,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CatalogTag(
    text: String,
    accent: Color = Color(0xFFCBD5E1),
) {
    Box(
        modifier = Modifier
            .background(Color(0xCC0F172A), RoundedCornerShape(6.dp))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CatalogActionButton(
    text: String,
    modifier: Modifier = Modifier,
    background: Brush,
    foreground: Color,
    border: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) foreground else foreground.copy(alpha = 0.45f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun providerUiMeta(provider: ModelProvider): ProviderUiMeta = when (provider) {
    ModelProvider.PRISMML -> ProviderUiMeta(
        selectorName = "Bonsai",
        selectorSubtitle = "PrismML",
        sectionName = "PrismML (Bonsai)",
        badge = "1-bit",
        tagline = "Modelos de 1 bit e ternários: muita capacidade em pouca memória",
        accent = Color(0xFF4ADE80),
    )

    ModelProvider.DEEPSEEK -> ProviderUiMeta(
        selectorName = "DeepSeek",
        selectorSubtitle = "Raciocínio R1",
        sectionName = "DeepSeek",
        badge = "Open Weights",
        tagline = "Modelos de raciocínio de alta eficiência e baixo custo",
        accent = Color(0xFF22D3EE),
    )

    ModelProvider.ALIBABA -> ProviderUiMeta(
        selectorName = "Qwen",
        selectorSubtitle = "Alibaba Cloud",
        sectionName = "Alibaba Cloud (Qwen)",
        badge = "Qwen",
        tagline = "Geração de código, matemática e contexto longo",
        accent = Color(0xFFFBBF24),
    )

    ModelProvider.MISTRAL -> ProviderUiMeta(
        selectorName = "Mistral",
        selectorSubtitle = "Mistral AI",
        sectionName = "Mistral AI",
        badge = "Mistral",
        tagline = "Instruções e linguagem em múltiplos idiomas",
        accent = Color(0xFFFB923C),
    )

    ModelProvider.META -> ProviderUiMeta(
        selectorName = "Llama",
        selectorSubtitle = "Meta AI",
        sectionName = "Meta AI",
        badge = "Llama",
        tagline = "Família Llama otimizada para execução local",
        accent = Color(0xFF818CF8),
    )

    ModelProvider.GOOGLE -> ProviderUiMeta(
        selectorName = "Gemma",
        selectorSubtitle = "Google DeepMind",
        sectionName = "Google (Gemma)",
        badge = "Gemma",
        tagline = "Modelos compactos da família Gemma",
        accent = Color(0xFFFB7185),
    )

    ModelProvider.MICROSOFT -> ProviderUiMeta(
        selectorName = "Phi",
        selectorSubtitle = "Microsoft",
        sectionName = "Microsoft (Phi)",
        badge = "Phi",
        tagline = "Modelos compactos de alta densidade lógica",
        accent = Color(0xFF34D399),
    )

    ModelProvider.IBM -> ProviderUiMeta(
        selectorName = "Granite",
        selectorSubtitle = "IBM",
        sectionName = "IBM / Granite",
        badge = "Granite",
        tagline = "Modelos abertos da família Granite",
        accent = Color(0xFF60A5FA),
    )

    ModelProvider.LIQUID -> ProviderUiMeta(
        selectorName = "Liquid",
        selectorSubtitle = "Liquid AI",
        sectionName = "Liquid AI",
        badge = "LFM",
        tagline = "Modelos compactos para execução eficiente",
        accent = Color(0xFFA78BFA),
    )

    ModelProvider.AI2 -> ProviderUiMeta(
        selectorName = "OLMo",
        selectorSubtitle = "Ai2",
        sectionName = "Ai2 / OLMo",
        badge = "OLMo",
        tagline = "Modelos abertos de pesquisa da Ai2",
        accent = Color(0xFF2DD4BF),
    )

    ModelProvider.NVIDIA -> ProviderUiMeta(
        selectorName = "Nemotron",
        selectorSubtitle = "NVIDIA",
        sectionName = "NVIDIA",
        badge = "Nemotron",
        tagline = "Modelos NVIDIA para raciocínio e agentes",
        accent = Color(0xFFA3E635),
    )

    ModelProvider.OPENAI -> ProviderUiMeta(
        selectorName = "gpt-oss",
        selectorSubtitle = "OpenAI",
        sectionName = "OpenAI",
        badge = "gpt-oss",
        tagline = "Modelos abertos da OpenAI com raciocínio",
        accent = Color(0xFFE5E7EB),
    )

    ModelProvider.OPENBMB -> ProviderUiMeta(
        selectorName = "MiniCPM",
        selectorSubtitle = "OpenBMB",
        sectionName = "OpenBMB (MiniCPM)",
        badge = "MiniCPM",
        tagline = "Modelos pequenos feitos para rodar no aparelho",
        accent = Color(0xFFF472B6),
    )

    ModelProvider.JETBRAINS -> ProviderUiMeta(
        selectorName = "Mellum",
        selectorSubtitle = "JetBrains",
        sectionName = "JetBrains (Mellum)",
        badge = "Código",
        tagline = "Modelos da JetBrains especializados em programação",
        accent = Color(0xFFFACC15),
    )

    ModelProvider.ORNITH -> ProviderUiMeta(
        selectorName = "Ornith",
        selectorSubtitle = "Ornith AI",
        sectionName = "Ornith AI",
        badge = "Ornith",
        tagline = "Modelos com treino de autoaperfeiçoamento, fortes em raciocínio e código",
        accent = Color(0xFFF97316),
    )
}
