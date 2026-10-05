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

val Cream = Color(0xFFF7F6EF)
val Forest = Color(0xFF274C3A)
val Ink = Color(0xFF243B2E)
val Muted = Color(0xFF6D786B)
val Lime = Color(0xFFD9E9A3)
val Mist = Color(0xFFEAF0E2)
val Clay = Color(0xFFB75D3E)
val Line = Color(0xFFDDE1D4)

@Composable
fun TopBrand(vm: GardenModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Spa, "", tint = Forest, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(8.dp))
        Text("meal garden", fontFamily = FontFamily.Serif, fontSize = 23.sp, color = Forest)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(CircleShape)
                .background(Mist)
                .clickable { vm.openSettings = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(if (vm.online) Forest else Clay, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                if (vm.online) "Connected" else if (vm.paired) "Offline" else "Connect",
                fontSize = 11.sp,
                color = Forest,
            )
        }
        IconButton(onClick = { vm.openSettings = true }) {
            Icon(Icons.Outlined.Tune, "Settings", tint = Forest)
        }
    }
}

@Composable
fun Eyebrow(text: String, color: Color = Muted) {
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
        color = color,
    )
}

@Composable
fun Heading(title: String, subtitle: String = "", action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = FontFamily.Serif,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                color = Ink,
            )
            if (subtitle.isNotEmpty())
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 7.dp),
                    color = Muted,
                    fontSize = 14.sp,
                )
        }
        action?.invoke()
    }
}

@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .border(1.dp, Line.copy(alpha = .7f), RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector = Icons.AutoMirrored.Outlined.ArrowForward,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text)
        Spacer(Modifier.width(10.dp))
        Icon(icon, null, Modifier.size(18.dp))
    }
}

@Composable
fun SectionLabel(title: String, action: String = "", onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 19.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (action.isNotEmpty()) TextButton(onClick = onClick) { Text(action, fontSize = 12.sp) }
    }
}

@Composable
fun Note(text: String, icon: ImageVector = Icons.Outlined.Info) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Mist).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = Forest, modifier = Modifier.size(18.dp))
        Text(text, color = Forest, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
fun Pill(text: String, color: Color = Mist) {
    Text(
        text,
        Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 6.dp),
        fontSize = 11.sp,
        color = Forest,
    )
}

@Composable
fun GardenBowl(modifier: Modifier = Modifier, seed: Int = 0) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = min(w, h) * .43f
        val c = Offset(w * .5f, h * .5f)
        drawCircle(Color(0xFF193E2C).copy(alpha = .10f), r * 1.12f, c + Offset(0f, 6f))
        drawCircle(Color(0xFFF7F5E8), r, c)
        drawCircle(Color(0xFFE2D8B7), r * .88f, c)
        for (i in 0..25) {
            val a = (i * 2.4 + seed).toFloat()
            val rr = r * (.2f + .58f * ((i % 7) / 7f))
            val pt = c + Offset(cos(a) * rr, sin(a) * rr)
            val color =
                listOf(
                    Color(0xFF557749),
                    Color(0xFF8AAB59),
                    Color(0xFFBA6A40),
                    Color(0xFFDFCA8F),
                    Color(0xFFD9E8AA),
                )[(i + seed.absoluteValue) % 5]
            drawCircle(color, r * (.12f + (i % 3) * .035f), pt)
            if (i % 3 == 0)
                drawLine(
                    Color.White.copy(alpha = .25f),
                    pt - Offset(r * .04f, r * .04f),
                    pt + Offset(r * .03f, r * .03f),
                    2f,
                )
        }
        drawArc(
            Color.White.copy(alpha = .7f),
            195f,
            75f,
            false,
            c - Offset(r, r),
            Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(r * .045f),
        )
    }
}

@Composable
fun QuickTile(
    title: String,
    sub: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Mist)
            .clickable(onClick = onClick)
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, null, tint = Forest, modifier = Modifier.size(23.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(sub, fontSize = 11.sp, color = Muted)
    }
}

@Composable
fun RecipeTile(r: JSONObject, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        GardenBowl(
            Modifier.size(76.dp).clip(RoundedCornerShape(17.dp)).background(Mist),
            r.s("id").hashCode().absoluteValue % 12,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(r.s("title"), fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 23.sp)
            Text(
                if (r.s("readiness") == "ready")
                    "${r.optInt("total_minutes")} min  ·  ${r.optInt("active_minutes")} min hands-on"
                else "From the archive · needs recipe review",
                color = Muted,
                fontSize = 11.sp,
            )
            val evidence = recipeEvidence(r)
            if (evidence.isNotEmpty()) Text(
                healthGroups.filter { evidence.containsKey(it.id) }.take(3).joinToString(" · ") { it.label },
                color = Forest, fontSize = 11.sp,
            )
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
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
        Text(text, fontSize = 15.sp, lineHeight = 25.sp, color = Ink)
    }
}

@Composable
fun NativePanel(vm: GardenModel, p: JSONObject) {
    CardBox(color = Mist) {
        Eyebrow("FROM YOUR ASSISTANT")
        Text(p.s("title"), fontFamily = FontFamily.Serif, fontSize = 23.sp)
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
