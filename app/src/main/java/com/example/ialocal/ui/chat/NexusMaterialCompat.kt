package com.example.ialocal.ui.chat

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Mantém a chamada do composer expressiva sem acoplar a tela à API concreta
 * de IconButtonDefaults usada pela versão do Material 3 do projeto.
 */
@Composable
fun ButtonDefaults.filledIconButtonColors(
    containerColor: Color,
): IconButtonColors = IconButtonDefaults.filledIconButtonColors(
    containerColor = containerColor,
)
