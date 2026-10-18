package com.example.ialocal.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CanvasBg = Color(0xFF0B0E17)
private val CanvasCard = Color(0xFF161A29)
private val CanvasBorder = Color(0xFF23283E)
private val CanvasIndigo = Color(0xFF4F46E5)
private val CanvasText = Color(0xFFF1F5F9)
private val CanvasMuted = Color(0xFF94A3B8)
private val CanvasDim = Color(0xFF64748B)

private enum class CanvasTab { CODE, RESULT }

/** A code block in the conversation: title, what it is and an "Abrir" button, like Gemini's Canvas. */
@Composable
internal fun CodeCanvasCard(canvas: CodeCanvas, createdAt: Long, onOpen: () -> Unit) {
    val date = remember(createdAt) { formatCanvasDate(createdAt) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        color = CanvasCard,
        border = BorderStroke(1.dp, CanvasBorder),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .border(1.5.dp, CanvasText.copy(alpha = 0.85f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = CanvasText, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        canvas.title,
                        color = CanvasText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (canvas.complete) {
                        Text(
                            "${canvas.languageLabel} · ${canvas.lineCount} linhas · $date",
                            color = CanvasMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = CanvasMuted)
                            Spacer(Modifier.width(6.dp))
                            Text("Escrevendo… ${canvas.lineCount} linhas", color = CanvasMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    modifier = Modifier.clickable(onClick = onOpen),
                    shape = RoundedCornerShape(50),
                    color = CanvasIndigo,
                ) {
                    Text(
                        "Abrir",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** A short code block that stays in the answer. */
@Composable
internal fun InlineCodeBlock(part: AnswerPart.Code) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CanvasBg,
        border = BorderStroke(1.dp, CanvasBorder),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (part.language.isNotEmpty()) {
                Text(part.language, color = CanvasDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
            }
            SelectionContainer {
                Text(
                    part.code,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    color = Color(0xFFE2E8F0),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
    }
}

/** The code full screen, with "Código" and, when the code can be run here, "Ver resultado". */
@Composable
internal fun CodeCanvasDialog(canvas: CodeCanvas, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val previewable = canvas.preview != null
    var tab by remember { mutableStateOf(if (previewable && canvas.complete) CanvasTab.RESULT else CanvasTab.CODE) }
    var reloads by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = CanvasBg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = CanvasText)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(canvas.title, color = CanvasText, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (canvas.complete) "${canvas.languageLabel} · ${canvas.lineCount} linhas" else "Escrevendo… ${canvas.lineCount} linhas",
                            color = CanvasMuted,
                            fontSize = 11.sp,
                        )
                    }
                    if (tab == CanvasTab.RESULT) {
                        IconButton(onClick = { reloads += 1 }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recarregar", tint = CanvasText)
                        }
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(canvas.code)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar código", tint = CanvasText)
                    }
                }

                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .background(CanvasCard, RoundedCornerShape(50))
                        .border(1.dp, CanvasBorder, RoundedCornerShape(50))
                        .padding(4.dp),
                ) {
                    CanvasTabButton("Código", selected = tab == CanvasTab.CODE, enabled = true, modifier = Modifier.weight(1f)) {
                        tab = CanvasTab.CODE
                    }
                    CanvasTabButton("Ver resultado", selected = tab == CanvasTab.RESULT, enabled = previewable, modifier = Modifier.weight(1f)) {
                        tab = CanvasTab.RESULT
                    }
                }
                if (!previewable) {
                    Text(
                        "Ver resultado roda HTML, SVG e JavaScript.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        color = CanvasDim,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                    when (tab) {
                        CanvasTab.CODE -> CanvasCode(canvas.code)
                        CanvasTab.RESULT -> {
                            val document = remember(canvas) { CodeCanvases.previewDocument(canvas) }
                            if (canvas.complete && document != null) {
                                CanvasPreview(document, reloads)
                            } else {
                                Text(
                                    "O resultado aparece quando a IA terminar de escrever o código.",
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                    color = CanvasMuted,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasTabButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(if (selected) CanvasIndigo else Color.Transparent, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                selected -> Color.White
                enabled -> CanvasText
                else -> CanvasDim
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The code with line numbers; long lines scroll sideways instead of wrapping. */
@Composable
private fun CanvasCode(code: String) {
    val numbers = remember(code) { (1..code.lines().size).joinToString("\n") }
    Row(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Text(
            numbers,
            modifier = Modifier.padding(start = 12.dp, end = 10.dp),
            color = CanvasDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                code,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(end = 16.dp),
                color = Color(0xFFE2E8F0),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}

/**
 * Renders the page in a WebView, the phone's own browser engine: no internet is needed unless the
 * page itself loads something from it. It cannot read the app's files, and links leaving the page
 * are not followed.
 */
// JavaScript is what makes a generated site interactive; the page gets no bridge into the app.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CanvasPreview(document: String, reloads: Int) {
    val loadKey = "$reloads:${document.hashCode()}"
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !request.url.toString().startsWith(CodeCanvases.PREVIEW_BASE_URL)
                }
            }
        },
        update = { webView ->
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL(CodeCanvases.PREVIEW_BASE_URL, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun formatCanvasDate(millis: Long): String =
    SimpleDateFormat("d 'de' MMM, HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(millis))
