package online.ptsmovies.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

val CardShape = RoundedCornerShape(22.dp)
val RowShape = RoundedCornerShape(16.dp)
val FieldShape = RoundedCornerShape(14.dp)
val ButtonShape = RoundedCornerShape(14.dp)
val PosterShape = RoundedCornerShape(12.dp)

@Composable
fun PtsBackground(modifier: Modifier = Modifier, decorative: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val accent = Pts.accent
    val spark = Pts.spark
    val black = LocalPitchBlack.current
    Box(
        Modifier.fillMaxSize().background(Pts.bg).then(modifier).drawBehind {
            if (!decorative || black) return@drawBehind
            drawRect(Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.03f),
                radius = size.width * 0.95f
            ))
            drawRect(Brush.radialGradient(
                colors = listOf(spark.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(size.width * 0.04f, size.height * 0.48f),
                radius = size.width * 0.8f
            ))
        },
        content = content
    )
}

@Composable
fun PtsTopBar(title: String, initial: String, onAvatar: () -> Unit, onTitle: () -> Unit = {}, avatar: String? = null, imageLoader: coil.ImageLoader? = null) {
    val line = Pts.line
    Row(
        Modifier.fillMaxWidth().background(Pts.card.copy(alpha = 0.96f))
            .drawBehind {
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(39.dp)
                .shadow(7.dp, RoundedCornerShape(13.dp), ambientColor = Pts.accent.copy(alpha = .3f), spotColor = Pts.accent.copy(alpha = .3f))
                .background(Pts.accent, RoundedCornerShape(13.dp))
                .border(2.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("PTS", fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Pts.onAccent)
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 11.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTitle),
            fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 19.sp, color = Pts.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier.size(48.dp).testTag("profile-avatar").background(Pts.soft, CircleShape)
                .border(2.dp, Pts.line2, CircleShape)
                .semantics { contentDescription = "Open profile" }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAvatar),
            contentAlignment = Alignment.Center
        ) {
            Text(initial.ifBlank { "?" }, fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Pts.accent)
            if (avatar != null && imageLoader != null) AsyncImage(avatar, "Profile photo", imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun PtsPage(title: String, subtitle: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Pts.text, modifier = Modifier.weight(1f))
            trailing?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
        }
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Pts.muted, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun PtsSectionHeader(title: String, count: Int? = null, action: (@Composable () -> Unit)? = null) {
    val line = Pts.line
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp)
            .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("✦", color = Pts.spark, fontSize = 16.sp, modifier = Modifier.padding(end = 7.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Pts.text, modifier = Modifier.weight(1f))
        if (count != null) Text("$count", style = MaterialTheme.typography.labelLarge, color = Pts.accent)
        action?.invoke()
    }
}

@Composable
fun PtsCard(
    modifier: Modifier = Modifier,
    color: Color = Pts.card,
    borderColor: Color = Pts.line,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = CardShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null && ptsMotionEnabled()) .99f else 1f, tween(motionMillis(ptsMotionEnabled(), 120)), label = "card-press")
    Column(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(9.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .clip(shape).background(color)
            .border(2.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun PtsPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val motion = ptsMotionEnabled()
    val scale by animateFloatAsState(if (pressed && motion) 0.975f else 1f, tween(motionMillis(motion, 120)), label = "press")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick),
        content = content
    )
}

@Composable
fun PtsPrimaryButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, tint: Color = Pts.accent, onClick: () -> Unit) {
    val shape = ButtonShape
    val bg by animateColorAsState(if (enabled) tint else Pts.line, tween(motionMillis(ptsMotionEnabled(), 150)), label = "primary")
    PtsPressable(onClick = onClick, enabled = enabled, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .shadow(if (enabled) 7.dp else 0.dp, shape, ambientColor = tint.copy(alpha = .35f), spotColor = tint.copy(alpha = .45f))
                .clip(shape).background(bg)
                 .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(19.dp), tint = if (enabled) Pts.onAccent else Pts.muted)
            if (icon != null) Spacer(Modifier.width(8.dp))
            Text(label, fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (enabled) Pts.onAccent else Pts.muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PtsSecondaryButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val shape = ButtonShape
    val tint = if (danger) Pts.danger else Pts.text
    val border = if (danger) Pts.danger.copy(alpha = .55f) else Pts.line2
    PtsPressable(onClick = onClick, enabled = enabled, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(Pts.card)
                .border(2.dp, border, shape).padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) tint else Pts.muted)
            if (icon != null) Spacer(Modifier.width(7.dp))
            Text(label, fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (enabled) tint else Pts.muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PtsIconAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Pts.text, size: Dp = 48.dp) {
    PtsPressable(onClick, Modifier.size(size).clip(CircleShape)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(20.dp), tint = tint) }
    }
}

@Composable
fun PtsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true
) {
    var revealed by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val outline by animateColorAsState(if (focused) Pts.accent else Pts.line, tween(motionMillis(ptsMotionEnabled(), 150)), label = "field-focus")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Pts.muted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(fontFamily = DmSans, fontSize = 15.sp, color = Pts.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Pts.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            interactionSource = source,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clip(FieldShape).background(Pts.soft)
                .border(2.dp, outline, FieldShape)
                .padding(horizontal = 15.dp, vertical = 15.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, fontFamily = DmSans, fontSize = 15.sp, color = Pts.muted2.copy(alpha = .75f))
                        inner()
                    }
                    if (password) Text(
                        if (revealed) "HIDE" else "SHOW",
                        style = MaterialTheme.typography.labelSmall, color = Pts.muted,
                        modifier = Modifier.padding(start = 10.dp).clickable { revealed = !revealed }
                    )
                }
            }
        )
    }
}

@Composable
fun PtsSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val outline by animateColorAsState(if (focused) Pts.accent else Pts.line, tween(motionMillis(ptsMotionEnabled(), 150)), label = "search-focus")
    Row(
        modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RowShape).background(Pts.card)
            .border(2.dp, outline, RowShape)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(PtsIcons.Search, null, Modifier.size(20.dp), tint = Pts.muted)
        BasicTextField(
            value, onValueChange, singleLine = true,
            textStyle = TextStyle(fontFamily = DmSans, fontSize = 15.sp, color = Pts.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Pts.accent),
            interactionSource = source,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, fontFamily = DmSans, fontSize = 15.sp, color = Pts.muted2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) PtsIconAction(PtsIcons.Close, "Clear search", { onValueChange("") }, tint = Pts.muted, size = 40.dp)
    }
}

@Composable
fun PtsSegmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RowShape).background(Pts.soft).border(2.dp, Pts.line, RowShape).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val bg by animateColorAsState(if (active) Pts.accent else Color.Transparent, tween(motionMillis(ptsMotionEnabled(), 160)), label = "seg")
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(bg).semantics { this.selected = active }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, Modifier.padding(horizontal = 2.dp, vertical = 8.dp), fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = if (active) Pts.onAccent else Pts.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PtsPill(text: String, active: Boolean = false, danger: Boolean = false, tint: Color? = null) {
    val color = tint ?: when {
        danger -> Pts.danger
        active -> Pts.accent
        else -> Pts.muted
    }
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (active) Pts.accentSoft else if (danger) Pts.dangerSoft else Pts.soft)
            .border(1.dp, if (active) Pts.accent.copy(alpha = .55f) else Pts.line2.copy(alpha = .55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text.uppercase(), fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = .5.sp, color = color)
    }
}

@Composable
fun PtsProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 7.dp, tint: Color = Pts.accent) {
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val visual by animateFloatAsState(safeProgress, tween(motionMillis(ptsMotionEnabled(), 180)), label = "transfer-progress")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(99.dp)).background(Pts.soft)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(visual).clip(RoundedCornerShape(99.dp)).background(tint))
    }
}

@Composable
fun PtsEmpty(title: String, body: String? = null, actionLabel: String? = null, icon: ImageVector = PtsIcons.Info, onAction: (() -> Unit)? = null) {
    val line = Pts.line2
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(Pts.card)
            .drawBehind {
                drawRoundRect(
                    color = line,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())
                )
            }
            .padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(icon, null, Modifier.size(34.dp), tint = Pts.accent)
        Text(title, style = MaterialTheme.typography.titleMedium, color = Pts.text, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = Pts.muted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(2.dp))
            PtsSecondaryButton(actionLabel, Modifier.widthIn(max = 240.dp), onClick = onAction)
        }
    }
}

@Composable
fun PtsErrorBanner(text: String, ok: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RowShape)
            .background(if (ok) Pts.okSoft else Pts.dangerSoft)
            .border(2.dp, if (ok) Pts.ok.copy(alpha = .6f) else Pts.danger.copy(alpha = .6f), RowShape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (ok) PtsIcons.Check else PtsIcons.Info, null, Modifier.size(18.dp), tint = if (ok) Pts.ok else Pts.danger)
        Text(text, Modifier.padding(start = 9.dp), style = MaterialTheme.typography.bodySmall,
            color = if (ok) Pts.ok else Pts.danger)
    }
}

@Composable
fun PtsStatTile(
    label: String,
    value: String,
    sub: String,
    fraction: Float,
    tint: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val line = Pts.line2
    Column(
        modifier.clip(RoundedCornerShape(23.dp)).background(tint.copy(alpha = 0.09f))
            .border(2.dp, Pts.line, RoundedCornerShape(23.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2
                drawArc(line.copy(alpha = .8f), 0f, 360f, false,
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(tint, -90f, 360f * fraction.coerceIn(0f, 1f), false,
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(value, fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 21.sp, color = Pts.text)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(tint, CircleShape))
            Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = Pts.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Pts.muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PtsNetworkTile(down: String, up: String, total: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(23.dp)).background(Pts.accent.copy(alpha = 0.06f))
            .border(2.dp, Pts.line, RoundedCornerShape(23.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            listOf(PtsIcons.NetworkDown to down, PtsIcons.NetworkUp to up).forEach { (icon, value) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(42.dp).border(2.dp, Pts.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(23.dp), tint = Pts.accent)
                    }
                    Text(value, style = MaterialTheme.typography.labelSmall, color = Pts.text, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(Pts.accent, CircleShape))
            Text("Network", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = Pts.text)
        }
        Text(total, style = MaterialTheme.typography.bodySmall, color = Pts.muted, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun PtsPosterCard(
    title: Title,
    resume: Resume?,
    marked: Boolean,
    modifier: Modifier = Modifier,
    width: Dp? = 138.dp,
    onOpen: () -> Unit,
    onToggleWatch: (() -> Unit)? = null,
    onClearProgress: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && ptsMotionEnabled()) .975f else 1f, tween(motionMillis(ptsMotionEnabled(), 130)), label = "poster-press")
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .testTag("poster-card-${title.id}").clip(RoundedCornerShape(19.dp)).background(Pts.card)
            .border(2.dp, Pts.line, RoundedCornerShape(19.dp)).clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onOpen).padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).testTag("poster-art-${title.id}").clip(PosterShape).background(Pts.soft)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(title.title.take(1).uppercase(), fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 34.sp, color = Pts.accent)
            }
            if (title.poster.startsWith("https://")) {
                AsyncImage(title.poster, "Poster for ${title.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            val seconds = resume?.seconds ?: 0L
            val total = resume?.duration ?: 0L
            if (seconds > 0 && total > 0) {
                val fraction = (seconds.toFloat() / total.toFloat()).coerceIn(0.03f, 1f)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = .45f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Pts.spark))
                }
            }
            if (onToggleWatch != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp)
                        .background(if (marked) Pts.accent else Pts.card.copy(alpha = .94f), CircleShape)
                        .border(1.dp, if (marked) Pts.accent else Pts.line2, CircleShape)
                        .semantics { contentDescription = if (marked) "Remove ${title.title} from My List" else "Add ${title.title} to My List" }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggleWatch),
                    contentAlignment = Alignment.Center
                ) { Icon(if (marked) PtsIcons.Check else PtsIcons.Plus, null, Modifier.size(16.dp), tint = if (marked) Pts.onAccent else Pts.accent) }
            }
            if (onClearProgress != null) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).background(Pts.card.copy(alpha = .94f), CircleShape)) {
                PtsIconAction(PtsIcons.Close, "Remove from Continue watching", onClearProgress, tint = Pts.text, size = 32.dp)
            }
        }
        val titleStyle = MaterialTheme.typography.titleSmall
        val titleHeight = with(LocalDensity.current) { (titleStyle.lineHeight * 2).toDp() + 4.dp }
        Text(title.title, style = titleStyle, color = Pts.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(titleHeight).padding(horizontal = 2.dp))
    }
}

@Composable
fun PtsPosterShelf(label: String, titles: List<Title>, progress: Map<Long, Resume>, watch: Set<Long>, onOpen: (Title) -> Unit, onToggle: (Title) -> Unit, onClear: ((Title) -> Unit)? = null) {
    Column {
        PtsSectionHeader(label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
            items(titles, key = { it.id }) { title ->
                PtsPosterCard(title, progress[title.id], title.id in watch, onOpen = { onOpen(title) }, onToggleWatch = { onToggle(title) },
                    onClearProgress = onClear?.let { { it(title) } })
            }
        }
    }
}

@Composable
fun PtsHero(title: Title, marked: Boolean, onOpen: () -> Unit, onPlay: () -> Unit, onToggleWatch: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().heightIn(min = 310.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF201944))
            .border(2.dp, Pts.line, RoundedCornerShape(26.dp))
    ) {
        val wide = maxWidth >= 540.dp
        if (title.poster.startsWith("https://")) {
            AsyncImage(title.poster, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = 0.6f)
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            listOf(Color(0xF2141434), Color(0xCC141434), Color(0x66141434))
        )))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp).padding(top = 36.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (wide) Box(
                Modifier.width(96.dp).aspectRatio(2f / 3f).clip(PosterShape).background(Pts.soft)
                    .border(2.dp, Color.White.copy(alpha = .46f), PosterShape)
            ) {
                if (title.poster.startsWith("https://")) AsyncImage(title.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = if (wide) 14.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FEATURED · ${if (title.kind == "series") "SERIES" else "MOVIE"}", style = MaterialTheme.typography.labelSmall, color = Pts.spark, letterSpacing = 1.sp)
                Text(title.title, style = MaterialTheme.typography.headlineLarge, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(title.year.takeIf { it > 0 }?.toString(), title.genres.take(2).joinToString()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFE7E8FF), maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                PtsButtonPair(first = { PtsPrimaryButton("Play", it, icon = PtsIcons.Play, onClick = onPlay) },
                    second = { PtsSecondaryButton(if (marked) "In list" else "My list", it, icon = if (marked) PtsIcons.Check else PtsIcons.Plus, onClick = onToggleWatch) })
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            PtsIconAction(PtsIcons.ChevronRight, "Open details", onOpen, tint = Color.White)
        }
    }
}

data class DockItem(val label: String, val icon: ImageVector, val value: String = label)

@Composable
fun PtsDock(items: List<DockItem>, selected: String, onSelect: (String) -> Unit) {
    val accent = Pts.accent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 72.dp)
            .shadow(16.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
            .clip(RoundedCornerShape(22.dp)).background(Pts.card.copy(alpha = .97f))
            .border(2.dp, Pts.line, RoundedCornerShape(22.dp)).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val active = item.value == selected
            val bg by animateColorAsState(if (active) Pts.accentSoft else Color.Transparent, tween(motionMillis(ptsMotionEnabled(), 180)), label = "dock")
            val tint by animateColorAsState(if (active) Pts.accent else Pts.muted, tween(motionMillis(ptsMotionEnabled(), 180)), label = "docktint")
            val lift by animateFloatAsState(if (active && ptsMotionEnabled()) -2f else 0f, tween(motionMillis(ptsMotionEnabled(), 180)), label = "dock-icon-lift")
            Box(
                Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(15.dp)).background(bg)
                    .semantics { this.selected = active }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = LocalIndication.current) { onSelect(item.value) }
                    .drawBehind {
                        if (active) drawRect(accent, topLeft = Offset(8.dp.toPx(), size.height - 3.dp.toPx()),
                            size = Size(size.width - 16.dp.toPx(), 3.dp.toPx()))
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(item.icon, item.label, Modifier.size(22.dp).graphicsLayer { translationY = lift }, tint = tint)
                    Text(item.label, fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp,
                        color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun PtsToast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(4200)
        onDismiss()
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        AnimatedVisibility(visible = message.isNotBlank(), enter = slideInVertically(tween(motionMillis(ptsMotionEnabled(), 180))) { it } + fadeIn(tween(motionMillis(ptsMotionEnabled(), 180))), exit = slideOutVertically(tween(motionMillis(ptsMotionEnabled(), 140))) { it } + fadeOut(tween(motionMillis(ptsMotionEnabled(), 140)))) {
            Row(
                Modifier.fillMaxWidth().shadow(14.dp, RowShape).clip(RowShape).background(Pts.card)
                    .border(2.dp, Pts.line, RowShape).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(PtsIcons.Info, null, Modifier.size(17.dp), tint = Pts.accent)
                Text(message, Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.bodySmall, color = Pts.text)
                PtsIconAction(PtsIcons.Close, "Dismiss", onDismiss, tint = Pts.muted, size = 28.dp)
            }
        }
    }
}

@Composable
fun PtsConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).ptsPageMotion(title).clip(RoundedCornerShape(24.dp)).background(Pts.card)
                .border(2.dp, Pts.line, RoundedCornerShape(24.dp)).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Pts.text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Pts.muted)
            content?.invoke()
            PtsButtonPair(first = { PtsSecondaryButton("Cancel", it, onClick = onDismiss) },
                second = { PtsPrimaryButton(confirmLabel, it, enabled = confirmEnabled, tint = if (destructive) Pts.danger else Pts.accent, onClick = onConfirm) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtsLoading(message: String = "Loading your library") {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(72.dp).shadow(18.dp, RoundedCornerShape(22.dp), ambientColor = Pts.accent.copy(alpha = .4f), spotColor = Pts.accent.copy(alpha = .5f))
                .background(Pts.accent, RoundedCornerShape(22.dp)).border(2.dp, Color.White.copy(alpha = .45f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) { Text("PTS", fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Pts.onAccent) }
        Spacer(Modifier.height(16.dp))
        Text("PTS Library", style = MaterialTheme.typography.headlineMedium, color = Pts.text)
        Spacer(Modifier.height(18.dp))
        androidx.compose.material3.CircularProgressIndicator(color = Pts.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Pts.muted, modifier = Modifier.padding(top = 10.dp))
    }
}
