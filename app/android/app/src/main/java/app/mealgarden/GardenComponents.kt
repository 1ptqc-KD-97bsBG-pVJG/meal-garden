@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.mealgarden

import androidx.compose.ui.platform.testTag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GardenTopBar(title: String, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = GardenSpace.Page, vertical = 6.dp)
        .heightIn(min = GardenSpace.Touch), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Forest)
        }
        Text(title, style = GardenType.Title, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun GardenBottomBar(selected: Int, onSelect: (Int) -> Unit, onCapture: () -> Unit) {
    val labels = listOf("Today", "Kitchen", "Capture", "Recipes", "More")
    val icons = listOf(Icons.Outlined.RadioButtonChecked, Icons.Outlined.Kitchen,
        Icons.Outlined.PhotoCamera, Icons.Outlined.MenuBook, Icons.Outlined.MoreHoriz)
    Column(Modifier.fillMaxWidth().background(Paper).navigationBarsPadding()) {
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (index == 2) {
                        Column(Modifier.testTag("nav:$label").offset(y = (-9).dp).size(58.dp)
                            .shadow(5.dp, CircleShape).clip(CircleShape).background(Forest)
                            .clickable(role = Role.Button, onClick = onCapture)
                            .semantics { contentDescription = label },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Icon(icons[index], null, tint = Paper, modifier = Modifier.size(25.dp))
                            Text(label, fontSize = 10.sp, color = Paper)
                        }
                    } else {
                        val active = index == selected
                        Column(Modifier.testTag("nav:$label").fillMaxWidth().height(60.dp).clip(GardenShape.Button)
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .semantics { this.selected = active },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                            Icon(icons[index], null, tint = if (active) Forest else Muted,
                                modifier = Modifier.size(23.dp))
                            Text(label, fontSize = 11.sp, color = if (active) Forest else Muted,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GardenCard(modifier: Modifier = Modifier, color: Color = CardSurface,
    onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(GardenShape.Card).background(color)
        .border(1.dp, Line, GardenShape.Card)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(GardenSpace.Card), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Inline panels leave the kitchen visible and allow another item to be selected. */
@Composable
fun GardenPanel(title: String, onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(GardenShape.Card).background(CardSurface)
        .border(1.dp, Forest, GardenShape.Card).padding(GardenSpace.Card),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = GardenType.Section, modifier = Modifier.weight(1f))
            if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, "Close panel", tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        content()
    }
}

@Composable
fun GardenSheet(title: String, onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper, contentColor = Ink,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = GardenSpace.Page)
            .padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = GardenType.Section, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, "Close sheet", tint = Muted)
                }
            }
            content()
        }
    }
}

@Composable
fun GardenChip(label: String, selected: Boolean = false, tint: Color = Paper2,
    icon: ImageVector? = null, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = if (onClick != null) 48.dp else 28.dp)
        .then(if (onClick != null) Modifier.clip(GardenShape.Chip)
            .clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected } else Modifier),
        contentAlignment = Alignment.Center) {
        Row(Modifier.clip(GardenShape.Chip).background(if (selected) Forest else tint)
            .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(15.dp),
                tint = if (selected) Paper else Forest)
            Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = if (selected) Paper else Muted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
fun GardenPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Paper),
        shape = GardenShape.Button, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = GardenType.Button, textAlign = TextAlign.Center)
    }
}

@Composable
fun GardenQuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Paper2, contentColor = Ink),
        shape = GardenShape.Button, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = GardenType.Button, textAlign = TextAlign.Center)
    }
}

@Composable
fun SparkleMark(modifier: Modifier = Modifier, color: Color = Forest) {
    Canvas(modifier.size(16.dp).semantics { contentDescription = "Assumed" }) {
        fun star(x: Float, y: Float, r: Float) {
            val path = Path().apply {
                moveTo(x, y - r); quadraticBezierTo(x + r * .2f, y - r * .2f, x + r, y)
                quadraticBezierTo(x + r * .2f, y + r * .2f, x, y + r)
                quadraticBezierTo(x - r * .2f, y + r * .2f, x - r, y)
                quadraticBezierTo(x - r * .2f, y - r * .2f, x, y - r); close()
            }
            drawPath(path, color)
        }
        star(size.width * .38f, size.height * .59f, size.minDimension * .35f)
        star(size.width * .80f, size.height * .21f, size.minDimension * .15f)
    }
}

enum class PantryStateMark(val label: String, val icon: ImageVector, val color: Color, val background: Color) {
    Fine("Fine", Icons.Outlined.Check, Forest, Mist),
    UseSoon("Use soon", Icons.Outlined.Schedule, Color(0xFF7A5410), AmberLight),
    UsedUp("Used up", Icons.Outlined.Remove, Muted, Paper2),
    Tossed("Tossed", Icons.Outlined.DeleteOutline, Color(0xFF7E2E1E), ClayLight),
}

@Composable
fun StateMark(state: PantryStateMark, modifier: Modifier = Modifier, showLabel: Boolean = true,
    selected: Boolean = true, assumed: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(modifier.clip(GardenShape.Button)
        .background(if (selected) state.background else Paper2)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { contentDescription = state.label; this.selected = selected }
        .heightIn(min = if (onClick != null) 48.dp else 28.dp)
        .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(state.icon, null, tint = state.color, modifier = Modifier.size(19.dp))
            if (assumed) SparkleMark(Modifier.size(12.dp))
        }
        if (showLabel) Text(state.label, fontSize = 11.sp, color = state.color)
    }
}

@Composable
fun StateKey(modifier: Modifier = Modifier, includeAssumed: Boolean = true) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PantryStateMark.entries.forEach { state ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(state.icon, state.label, tint = state.color, modifier = Modifier.size(15.dp))
                Text(state.label, style = GardenType.Small)
            }
        }
        if (includeAssumed) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            SparkleMark(); Text("Assumed", style = GardenType.Small)
        }
    }
}

@Composable
fun AmountStepper(value: Int, onChange: (Int) -> Unit, label: String = "Amount",
    modifier: Modifier = Modifier, min: Int = 0, max: Int = 99) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty()) Text(label, style = GardenType.Small, modifier = Modifier.padding(end = 6.dp))
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) },
            enabled = value > min, modifier = Modifier.size(48.dp), shape = GardenShape.Button,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Paper2, contentColor = Forest)) {
            Icon(Icons.Outlined.Remove, "Less $label", Modifier.size(19.dp))
        }
        Text(value.toString(), style = GardenType.Body.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.widthIn(min = 25.dp).semantics {
                contentDescription = "$label: $value"
            }, textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(max)) },
            enabled = value < max, modifier = Modifier.size(48.dp), shape = GardenShape.Button,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Paper2, contentColor = Forest)) {
            Icon(Icons.Outlined.Add, "More $label", Modifier.size(19.dp))
        }
    }
}

/** Values are 1 (a little), 2 (half), 3 (most), and 4 (full). */
@Composable
fun FourLevelAmount(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("A little", "Half", "Most", "Full").forEachIndexed { index, label ->
            val active = value == index + 1
            Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(GardenShape.Button)
                .background(if (active) Forest else Paper2)
                .clickable(role = Role.RadioButton) { onChange(index + 1) }
                .semantics { selected = active; contentDescription = label }
                .padding(horizontal = 3.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(4) { level ->
                        Box(Modifier.width(8.dp).height(4.dp).clip(CircleShape)
                            .background(if (level <= index) { if (active) Paper else Forest }
                                else if (active) Paper.copy(alpha = .25f) else Line))
                    }
                }
                Text(label, fontSize = 11.sp, color = if (active) Paper else Ink,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun GardenPlate(label: String, modifier: Modifier = Modifier, seed: Int = 0, count: Int = 1,
    empty: Boolean = false, drink: Boolean = false, mark: PlateMark? = null,
    food: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Column(modifier.width(86.dp)
        .then(if (onClick != null) Modifier.clip(GardenShape.Button)
            .clickable(role = Role.Button, onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp)) {
            if (count > 1) Canvas(Modifier.fillMaxSize()) {
                val c = center + Offset(-3.dp.toPx(), 3.dp.toPx())
                drawCircle(CardSurface, size.minDimension * .42f, c)
                drawCircle(Line, size.minDimension * .42f, c, style = Stroke(1.dp.toPx()))
            }
            if (empty) Canvas(Modifier.fillMaxSize()) {
                drawCircle(Faint, size.minDimension * .43f, center,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
            } else if (food == null) GardenBowl(Modifier.fillMaxSize(), seed)
            else {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(CardSurface, size.minDimension * .43f)
                    drawCircle(Line, size.minDimension * .43f, style = Stroke(1.dp.toPx()))
                    drawCircle(Paper2, size.minDimension * .37f)
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { food() }
            }
            if (empty) Text("?", style = GardenType.Title.copy(color = Faint),
                modifier = Modifier.align(Alignment.Center))
            if (count > 1) Text("×$count", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Paper, modifier = Modifier.align(Alignment.BottomStart)
                    .clip(GardenShape.Chip).background(Forest).padding(horizontal = 5.dp, vertical = 2.dp))
            if (drink) Icon(Icons.Outlined.LocalDrink, "Drink", tint = Forest,
                modifier = Modifier.align(Alignment.BottomEnd).clip(GardenShape.Button)
                    .background(CardSurface).padding(2.dp).size(15.dp))
            if (mark != null) Box(Modifier.align(Alignment.TopEnd).size(23.dp)
                .clip(CircleShape).background(CardSurface).border(1.dp, Line, CircleShape),
                contentAlignment = Alignment.Center) {
                PlateOriginMark(mark, Modifier.size(16.dp))
            }
        }
        Text(label, color = Muted, fontSize = 11.sp, lineHeight = 13.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun PlateOriginMark(mark: PlateMark, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics {
        contentDescription = if (mark == PlateMark.MadeToday) "Cooked today" else "Leftover"
    }) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.4.dp.toPx())
        if (mark == PlateMark.Leftover) {
            drawRoundRect(Forest, Offset(w * .12f, h * .37f), Size(w * .76f, h * .50f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f), style = stroke)
            drawLine(Forest, Offset(w * .07f, h * .32f), Offset(w * .93f, h * .32f), stroke.width)
            drawLine(Forest, Offset(w * .37f, h * .18f), Offset(w * .63f, h * .18f), stroke.width)
        } else {
            val path = Path().apply {
                moveTo(w * .27f, h * .69f); lineTo(w * .27f, h * .49f)
                cubicTo(-w * .02f, h * .49f, w * .08f, h * .08f, w * .33f, h * .20f)
                cubicTo(w * .32f, -h * .03f, w * .73f, -h * .03f, w * .69f, h * .21f)
                cubicTo(w * .98f, h * .07f, w * 1.02f, h * .49f, w * .74f, h * .50f)
                lineTo(w * .74f, h * .69f); close()
            }
            drawPath(path, Forest, style = stroke)
            drawRoundRect(Forest, Offset(w * .25f, h * .71f), Size(w * .50f, h * .13f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .04f), style = stroke)
        }
    }
}

@Composable
fun GardenStack(count: Int, modifier: Modifier = Modifier, seed: Int = 0) {
    Box(modifier.size(142.dp, 120.dp).semantics { contentDescription = "$count portions" }) {
        val visible = count.coerceIn(0, 5)
        repeat(visible) { index ->
            GardenBowl(Modifier.align(Alignment.Center).offset(y = (index * -7 + 15).dp).size(94.dp), seed)
        }
        Text("×$count", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .clip(GardenShape.Chip).background(Forest).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun PlateKey(modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(PlateMark.MadeToday to "Cooked today", PlateMark.Leftover to "Leftover").forEach { (mark, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PlateOriginMark(mark, Modifier.size(17.dp)); Text(label, style = GardenType.Small)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Outlined.LocalDrink, null, tint = Forest, modifier = Modifier.size(15.dp))
            Text("Drink", style = GardenType.Small)
        }
        Text("×2  Repeated", style = GardenType.Small)
    }
}

/** Ingredient icons share one plate, keeping the recipe distinguishable at a glance. */
@Composable
fun GardenRecipePlate(recipe: org.json.JSONObject, modifier: Modifier = Modifier) {
    val icons = recipePlateIconIds(recipe)
    BoxWithConstraints(modifier.semantics { contentDescription = "Ingredients for ${recipe.s("title")}" }) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(CardSurface, size.minDimension * .43f)
            drawCircle(Line, size.minDimension * .43f, style = Stroke(1.dp.toPx()))
            drawCircle(Paper2, size.minDimension * .36f)
        }
        val side = minOf(maxWidth, maxHeight)
        icons.forEachIndexed { index, id ->
            val angle = index * 2.0 * Math.PI / icons.size - Math.PI / 2
            val radius = if (icons.size == 1) 0.dp else side * .21f
            FoodIcon(id, Modifier.align(Alignment.Center)
                .offset(x = radius * cos(angle).toFloat(), y = radius * sin(angle).toFloat())
                .size(side * if (icons.size == 1) .53f else .35f))
        }
    }
}
