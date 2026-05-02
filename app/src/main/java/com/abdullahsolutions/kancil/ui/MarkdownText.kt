package com.abdullahsolutions.kancil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    Column(modifier = modifier) {
        val lines = text.lines()
        var i = 0
        var inCodeBlock = false
        val codeLines = mutableListOf<String>()

        while (i < lines.size) {
            val line = lines[i]

            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    CodeBlock(codeLines.joinToString("\n"), color)
                    codeLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                i++
                continue
            }
            if (inCodeBlock) {
                codeLines.add(line)
                i++
                continue
            }

            when {
                line.startsWith("### ") -> {
                    Text(
                        text  = parseInline(line.removePrefix("### ")),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = color
                    )
                    Spacer(Modifier.height(2.dp))
                }
                line.startsWith("## ") -> {
                    Text(
                        text  = parseInline(line.removePrefix("## ")),
                        style = MaterialTheme.typography.titleMedium,
                        color = color
                    )
                    Spacer(Modifier.height(2.dp))
                }
                line.startsWith("# ") -> {
                    Text(
                        text  = parseInline(line.removePrefix("# ")),
                        style = MaterialTheme.typography.titleLarge,
                        color = color
                    )
                    Spacer(Modifier.height(2.dp))
                }
                line.matches(Regex("^(\\d+)\\. .*")) -> {
                    val m       = Regex("^(\\d+)\\. (.*)").find(line)!!
                    val num     = m.groupValues[1]
                    val content = m.groupValues[2]
                    Row(Modifier.fillMaxWidth()) {
                        Text("$num. ", style = style, color = color)
                        Text(parseInline(content), style = style, color = color, modifier = Modifier.weight(1f))
                    }
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Row(Modifier.fillMaxWidth()) {
                        Text("• ", style = style, color = color)
                        Text(parseInline(line.drop(2)), style = style, color = color, modifier = Modifier.weight(1f))
                    }
                }
                line.startsWith("> ") -> {
                    Text(
                        text     = parseInline(line.removePrefix("> ")),
                        style    = style.copy(fontStyle = FontStyle.Italic),
                        color    = color,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                else           -> Text(parseInline(line), style = style, color = color)
            }
            i++
        }

        if (inCodeBlock && codeLines.isNotEmpty()) {
            CodeBlock(codeLines.joinToString("\n"), color)
        }
    }
}

@Composable
private fun CodeBlock(code: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x18000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text  = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color
        )
    }
}

private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            // Bold: **…** or __…__
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else append(text[i++])
            }
            text.startsWith("__", i) -> {
                val end = text.indexOf("__", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else append(text[i++])
            }
            // Strikethrough: ~~…~~
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else append(text[i++])
            }
            // Italic: *…* or _…_
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else append(text[i++])
            }
            text.startsWith("_", i) -> {
                val end = text.indexOf("_", i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else append(text[i++])
            }
            // Inline code: `…`
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else append(text[i++])
            }
            else -> append(text[i++])
        }
    }
}
