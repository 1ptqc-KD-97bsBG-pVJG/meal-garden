@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.mealgarden

import android.app.*
import android.content.*
import androidx.activity.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.*
import kotlin.math.*
import org.json.JSONObject

@Composable
fun Eyebrow(text: String, color: Color = Faint) {
    Text(text.uppercase(), style = GardenType.Label, color = color)
}

@Composable
fun Heading(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = GardenType.Title, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, color: Color = CardSurface,
    content: @Composable ColumnScope.() -> Unit) {
    GardenCard(modifier = modifier, color = color, content = content)
}

@Composable
fun ActionButton(text: String, icon: ImageVector = Icons.AutoMirrored.Outlined.ArrowForward,
    onClick: () -> Unit) {
    GardenPrimaryButton(text, onClick, icon = icon)
}

@Composable
fun SectionLabel(title: String, action: String = "", onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = GardenType.Section)
        Spacer(Modifier.weight(1f))
        if (action.isNotEmpty()) TextButton(onClick = onClick) {
            Text(action, style = GardenType.Small, color = Forest)
        }
    }
}

@Composable
fun Pill(text: String, color: Color = Paper2) {
    GardenChip(text, tint = color)
}

@Composable
fun GardenBowl(modifier: Modifier = Modifier, seed: Int = 0) {
    Canvas(modifier) {
        val r = size.minDimension * .43f
        val c = center
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(r * .018f)
        drawCircle(Color(0xFF796B4E).copy(alpha = .12f), r, c + Offset(0f, r * .09f))
        drawCircle(CardSurface, r, c)
        drawCircle(Line, r, c, style = stroke)
        drawCircle(Paper2, r * .81f, c)
        drawCircle(Line, r * .83f, c, style = stroke)
        // Each ingredient has its own recognizable shape and stays inside the plate.
        val turn = (seed % 4) * .35f
        for (i in 0 until 24) {
            val a = i * 2.399f + turn
            val rr = r * (.12f + .50f * ((i % 8) / 8f))
            val pt = c + Offset(cos(a) * rr, sin(a) * rr)
            drawOval(Color(0xFFE2D6AC), pt - Offset(r * .065f, r * .025f), Size(r * .13f, r * .05f))
        }
        for (i in 0 until 5) {
            val a = i * 1.2f + turn
            val pt = c + Offset(cos(a) * r * .43f, sin(a) * r * .43f)
            val veggie = if (i % 2 == 0) Leaf else Forest
            drawCircle(veggie, r * .16f, pt)
            drawCircle(veggie, r * .12f, pt + Offset(-r * .10f, r * .05f))
            drawCircle(veggie, r * .12f, pt + Offset(r * .08f, r * .06f))
            drawLine(Mist, pt, pt + Offset(r * .03f, r * .16f), r * .025f)
        }
        for (i in 0 until 4) {
            val a = i * 1.7f + turn
            val pt = c + Offset(cos(a) * r * .28f, sin(a) * r * .28f)
            drawRoundRect(Color(0xFFDDB779), pt - Offset(r * .10f, r * .11f),
                Size(r * .23f, r * .22f), androidx.compose.ui.geometry.CornerRadius(r * .04f))
            drawLine(Color(0xFFA16A41), pt - Offset(r * .04f, r * .04f),
                pt + Offset(r * .06f, r * .04f), r * .02f)
        }
        drawArc(Color.White.copy(alpha = .6f), 195f, 65f, false,
            c - Offset(r * .92f, r * .92f), Size(r * 1.84f, r * 1.84f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(r * .04f))
    }
}

@Composable
fun RichText(raw: String) {
    val text =
        remember(raw) {
            androidx.compose.ui.text.buildAnnotatedString {
                val regex = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)")
                var pos = 0
                regex.findAll(raw).forEach { m ->
                    append(raw.substring(pos, m.range.first).replace("**", "").replace("### ", ""))
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Url(
                            m.groupValues[2],
                            androidx.compose.ui.text.TextLinkStyles(
                                style =
                                    androidx.compose.ui.text.SpanStyle(
                                        color = Forest,
                                        textDecoration = TextDecoration.Underline,
                                    )
                            ),
                        )
                    ) {
                        append(m.groupValues[1])
                    }
                    pos = m.range.last + 1
                }
                append(raw.substring(pos).replace("**", "").replace("### ", ""))
            }
        }
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, style = GardenType.Body)
    }
}

@Composable
fun NativePanel(vm: GardenModel, p: JSONObject) {
    CardBox(color = Mist) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SparkleMark(); Text(p.s("title"), style = GardenType.Section)
        }
        RichText(p.s("body"))
        p.a("actions").objects().forEach { a ->
            OutlinedButton(
                onClick = {
                    if (a.s("type") == "recipe") vm.selectedRecipe = a.s("value")
                    else vm.ask(a.s("value"), origin = "native_card")
                },
                shape = RoundedCornerShape(13.dp),
            ) {
                Text(a.s("label"))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}
