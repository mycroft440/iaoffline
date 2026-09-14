package com.example.ialocal.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ialocal.data.ModelVerificationStatus
import com.example.ialocal.models.DeepThinkSupport

@Composable
fun ChatWithDeepThinkScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val readyModels = remember(models) { models.filter { it.verificationStatus == ModelVerificationStatus.VERIFIED.name } }
    val selectedAgent = agents.firstOrNull { it.id == conversation?.agentId } ?: agents.firstOrNull { it.isDefault }
    val selectedModel = readyModels.firstOrNull { it.id == selectedAgent?.modelId }
        ?: readyModels.firstOrNull { it.isActive }
        ?: readyModels.firstOrNull()
    val supported = selectedModel?.let { DeepThinkSupport.capability(it).supported } == true

    Box(Modifier.fillMaxSize()) {
        ChatScreen(
            viewModel = viewModel,
            onOpenConversation = onOpenConversation,
            onOpenHistory = onOpenHistory,
            onOpenModels = onOpenModels,
        )
        if (supported) {
            AssistChip(
                onClick = {},
                label = { Text("DeepThink") },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp),
            )
        }
    }
}
