package ch.trainingskompass.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * Gestaltung der Uhr-App: Aufbau wie Samsung Health (grosse Zahlen in Kacheln, Pulszonen-Bogen am Rand,
 * runder Start-Knopf), Farben wie die 4Peak-App (Verlauf Blau → Violett auf fast schwarzem Grund).
 */
object Brand {
    val Blue = Color(0xFF4A63FF)
    val Violet = Color(0xFF8A55FF)
    val Soft = Color(0xFF8EA2FF)
    val Tile = Color(0xFF15172A)
    val TileHi = Color(0xFF1E2140)
    val Muted = Color(0xFFAEB4C8)
    val grad: Brush get() = Brush.linearGradient(listOf(Blue, Violet))
    /** Hintergrund: oben ein Hauch Indigo, unten schwarz (OLED) */
    val bg: Brush get() = Brush.verticalGradient(0f to Color(0xFF181B42), 0.5f to Color(0xFF07080F), 1f to Color.Black)
    /** Pulszonen wie bei Samsung Health: blau, grün, gelb, orange, rot */
    val zones = listOf(Color(0xFF3D9BFF), Color(0xFF3DDC84), Color(0xFFFFD23F), Color(0xFFFF9F2E), Color(0xFFFF4D5E))
}

fun zoneColor(z: Int): Color = if (z in 1..5) Brand.zones[z - 1] else Brand.Muted

@Composable
fun gradChip(): ChipColors = ChipDefaults.gradientBackgroundChipColors(startBackgroundColor = Brand.Blue, endBackgroundColor = Brand.Violet)

@Composable
fun Logo(height: Dp) {
    Image(painterResource(R.drawable.ic_logo), contentDescription = "4Peak", modifier = Modifier.height(height))
}

/** Kachel mit kleiner Beschriftung und grosser Zahl */
@Composable
fun Tile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.White, unit: String? = null, big: Boolean = true) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Brand.Tile).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, color = Brand.Muted, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = if (big) 24.sp else 18.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            if (unit != null) Text(" $unit", fontSize = 11.sp, color = Brand.Muted, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** Runder Knopf im Verlauf (Start, Satz speichern) */
@Composable
fun GradButton(size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Brand.grad).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Runder Nebenknopf (zurück, weiter) */
@Composable
fun RoundButton(size: Dp, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Brand.TileHi).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
}

/** Pillenknopf; primary = Verlauf */
@Composable
fun Pill(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val m = Modifier.clip(RoundedCornerShape(50)).let { if (primary) it.background(Brand.grad) else it.background(Brand.TileHi) }
    Box(m.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** Sätze als Punkte: erledigt hell, aktueller Satz als Verlaufs-Strich */
@Composable
fun SetDots(done: Int, total: Int, warm: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total.coerceIn(1, 8)) { i ->
            val cur = i == done
            val m = Modifier.size(width = if (cur) 16.dp else 6.dp, height = 6.dp).clip(CircleShape)
            Box(
                when {
                    cur && !warm -> m.background(Brand.grad)
                    cur -> m.background(Brand.Soft)
                    i < done -> m.background(Brand.Soft)
                    else -> m.background(Color.White.copy(alpha = 0.18f))
                },
            )
        }
    }
}

@Composable
fun HeartIcon(color: Color, size: Dp = 14.dp) {
    Icon(painterResource(R.drawable.ic_heart), contentDescription = null, tint = color, modifier = Modifier.size(size))
}

/** Pulszonen-Bogen unten am Rand wie bei Samsung Health; die aktuelle Zone leuchtet */
@Composable
fun ZoneArc(zone: Int) {
    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
        val w = 5.dp.toPx()
        val inset = w
        val seg = 21f
        val gap = 3f
        val start = 90f + 2.5f * seg // links unten
        for (i in 0 until 5) {
            val on = zone == i + 1
            val sw = if (on) w * 1.5f else w
            drawArc(
                color = Brand.zones[i].copy(alpha = if (zone == 0 || on) 1f else 0.3f),
                startAngle = start - (i + 1) * seg + gap / 2,
                sweepAngle = seg - gap,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
    }
}

/** Fortschrittsring am Rand, oben offen für die Uhrzeit */
@Composable
fun EdgeProgress(progress: Float, color: Color = Brand.Soft) {
    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
        val w = 5.dp.toPx()
        val tl = Offset(w, w)
        val sz = Size(size.width - 2 * w, size.height - 2 * w)
        val start = -60f
        val total = 300f
        drawArc(color.copy(alpha = 0.16f), start, total, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) drawArc(Brush.sweepGradient(listOf(Brand.Blue, Brand.Violet, Brand.Blue)), start, total * p, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Dünner Rahmen im Verlauf für die gewählte Kachel */
fun Modifier.gradBorder(on: Boolean): Modifier = if (on) this.border(1.5.dp, Brand.grad, RoundedCornerShape(18.dp)) else this
