package com.example.ialocal.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.ialocal.AppContact
import com.example.ialocal.BuildConfig

/** Why an answer is reported; [label] is what the user picks and what the report says. */
enum class ReportReason(val label: String) {
    OFFENSIVE("Ofensiva ou com discurso de ódio"),
    SEXUAL("Conteúdo sexual"),
    DANGEROUS("Violência ou conteúdo perigoso"),
    MISLEADING("Informação falsa ou enganosa"),
    OTHER("Outro motivo"),
}

/**
 * The report of an AI answer, as the e-mail the user sends to the developer. The models run on the
 * phone and the app has no server, so nothing leaves the device until the user sends that e-mail.
 * Built apart from Android so it can be tested.
 */
object AnswerReport {
    const val SUBJECT = "Denúncia de resposta da IA - I.A Off-line"
    const val MAX_ANSWER_CHARS = 4_000
    const val MAX_DETAILS_CHARS = 500

    fun body(reason: ReportReason, modelLabel: String, answer: String, details: String, appVersion: String): String {
        val cleanAnswer = answer.trim()
        val cleanDetails = details.trim().take(MAX_DETAILS_CHARS)
        return buildString {
            appendLine("Motivo: ${reason.label}")
            appendLine("Modelo: ${modelLabel.trim().ifBlank { "não informado" }}")
            appendLine("Versão do app: $appVersion")
            if (cleanDetails.isNotEmpty()) appendLine("Detalhes: $cleanDetails")
            appendLine()
            appendLine("Resposta denunciada:")
            append(cleanAnswer.take(MAX_ANSWER_CHARS))
            if (cleanAnswer.length > MAX_ANSWER_CHARS) append("\n[resposta cortada em $MAX_ANSWER_CHARS caracteres]")
        }
    }
}

/**
 * The flag beside an AI answer that opens [ReportAnswerDialog]. Google Play requires apps that
 * generate content with AI to let users report an offensive answer from inside the app.
 */
@Composable
fun ReportAnswerAction(answer: String, modelLabel: String, tint: Color, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Icon(
        Icons.Outlined.Flag,
        contentDescription = "Denunciar resposta",
        tint = tint,
        modifier = modifier
            .clip(CircleShape)
            .clickable { open = true }
            .padding(4.dp)
            .size(16.dp),
    )
    if (open) ReportAnswerDialog(answer, modelLabel) { open = false }
}

@Composable
fun ReportAnswerDialog(answer: String, modelLabel: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reason by remember { mutableStateOf(ReportReason.OFFENSIVE) }
    var details by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Denunciar resposta") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("O que há de errado com esta resposta da IA?", style = MaterialTheme.typography.bodyMedium)
                ReportReason.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = reason == option,
                                role = Role.RadioButton,
                                onClick = { reason = option },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(AnswerReport.MAX_DETAILS_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Detalhes (opcional)") },
                    maxLines = 3,
                )
                Text(
                    "A IA roda no seu aparelho e o app não tem servidor. Ao continuar, seu app de e-mail abre com " +
                        "a denúncia pronta (motivo, modelo e o texto da resposta) para ${AppContact.EMAIL}. " +
                        "Nada é enviado antes de você tocar em enviar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    sendAnswerReport(context, reason, modelLabel, answer, details)
                    onDismiss()
                },
            ) { Text("Continuar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Opens the user's e-mail app with the report; without one, the report goes to the clipboard. */
private fun sendAnswerReport(
    context: Context,
    reason: ReportReason,
    modelLabel: String,
    answer: String,
    details: String,
) {
    val body = AnswerReport.body(reason, modelLabel, answer, details, BuildConfig.VERSION_NAME)
    val email = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(AppContact.EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, AnswerReport.SUBJECT)
        .putExtra(Intent.EXTRA_TEXT, body)
    try {
        context.startActivity(email)
    } catch (_: ActivityNotFoundException) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(AnswerReport.SUBJECT, body))
        Toast.makeText(
            context,
            "Nenhum app de e-mail encontrado. A denúncia foi copiada: envie para ${AppContact.EMAIL}.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
