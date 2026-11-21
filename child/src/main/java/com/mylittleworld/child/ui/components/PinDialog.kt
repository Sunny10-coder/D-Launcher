package com.mylittleworld.child.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mylittleworld.child.ui.theme.Palette

/**
 * Numeric PIN entry. Deliberately large targets and no system keyboard so the
 * child cannot accidentally type letters.
 */
@Composable
fun PinDialog(
    title: String,
    helper: String?,
    errorText: String?,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
    maxLength: Int = 8,
) {
    var digits by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.Cream,
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                helper?.let {
                    Text(it, fontSize = 15.sp, color = Palette.InkSoft, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(maxLength) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (maxLength > 6) 14.dp else 18.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < digits.length) Palette.LearningEnd else Palette.CreamDeep,
                                ),
                        )
                    }
                }
                errorText?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color(0xFFC0392B), fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { digit ->
                                KeypadKey(digit) {
                                    if (digits.length < maxLength) digits += digit
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeypadKey("⌫") { digits = digits.dropLast(1) }
                        KeypadKey("0") { if (digits.length < maxLength) digits += "0" }
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (digits.length >= 4) Palette.CallEnd else Palette.CreamDeep)
                                .clickable(enabled = digits.length >= 4) {
                                    val value = digits
                                    digits = ""
                                    onSubmit(value)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "✓",
                                fontSize = 26.sp,
                                color = if (digits.length >= 4) Color.White else Palette.InkSoft,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Palette.InkSoft, fontSize = 16.sp)
            }
        },
    )
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}
