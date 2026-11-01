package com.akira.island.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.data.IslandSettings
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Оплата вокруг острова — как в ролике. Пока ждём терминал, от острова расходятся сиреневые волны NFC;
 * когда оплата прошла, остров подпрыгивает, от него разлетаются зелёное кольцо и искры.
 * Выключается в «Эффектах» («Волны оплаты») и вместе со всеми — «Уменьшить эффекты».
 */
object PayFx {
    /** Насколько остров сейчас больше обычного: на «Оплачено» он на миг подрастает на 6% */
    val pop = mutableFloatStateOf(1f)
}

@Composable
fun PayEffects(primary: Act, body: () -> Rect, s: IslandSettings) {
    if (s.reduceEffects || !s.fxPay) return
    val notice = (primary as? Act.NoticeA)?.n
    when {
        primary is Act.PayA -> PayWaves(body, s.notch)
        notice?.style == NoticeStyle.PAY_DONE -> key(notice.id) { PayBurst(body, notice.tint, s.notch) }
    }
}

/**
 * Волны NFC — сдержанно: тонкое кольцо раз в 1,6 с отходит от острова всего на 24 dp и гаснет,
 * и так только три раза, потом остров спокойно ждёт терминал. Раньше волны шли без конца
 * и разлетались на 90 dp — при каждом открытии банка это мелькало на весь верх экрана.
 */
@Composable
private fun PayWaves(body: () -> Rect, notch: Boolean) {
    val p = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        repeat(3) {
            p.snapTo(0f)
            p.animateTo(1f, tween(1600, easing = LinearEasing))
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val b = body()
        if (b.isEmpty || p.value >= 1f) return@Canvas
        val e = ExpoOut.transform(p.value)
        val d = 3.dp.toPx() + e * 24.dp.toPx()
        val alpha = (1f - p.value) * 0.45f
        outline(b, d, Tints.Lavender.copy(alpha = alpha * 0.2f), 4.dp.toPx(), notch)
        outline(b, d, Tints.Lavender.copy(alpha = alpha), 1.2.dp.toPx(), notch)
    }
}

/** Успех: кольцо уходит от острова, искры разлетаются вниз и в стороны, остров подпрыгивает. */
@Composable
private fun PayBurst(body: () -> Rect, color: Color, notch: Boolean) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val pop = Animatable(1.06f)
        PayFx.pop.floatValue = 1.06f
        try {
            launch { pop.animateTo(1f, tween(450, easing = ExpoOut)) { PayFx.pop.floatValue = value } }
            p.animateTo(1f, tween(1000, easing = ExpoOut))
        } finally {
            PayFx.pop.floatValue = 1f
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val b = body()
        if (b.isEmpty || p.value >= 1f) return@Canvas
        val e = p.value
        outline(b, 4.dp.toPx() + e * 130.dp.toPx(), color.copy(alpha = 1f - e), 3.dp.toPx(), notch)
        // 22 искры веером вниз: зелёные и белые, как в ролике
        val c = b.center
        for (i in 0 until 22) {
            val a = Math.toRadians(8.0 + 164.0 * i / 21).toFloat()
            val spread = 18.dp.toPx() + e * (40.dp.toPx() + Seeds[i] * 50.dp.toPx())
            val x = c.x + cos(a) * (b.width / 2 + spread)
            val y = c.y + sin(a) * (b.height / 2 + spread * 0.8f)
            drawCircle(if (i % 2 == 0) color else Color.White, radius = 2.dp.toPx(), center = Offset(x, y), alpha = 1f - e)
        }
    }
}

/** Случайные, но одни и те же на каждом кадре — искры не мерцают. */
private val Seeds = FloatArray(22) { i -> ((sin(i * 127.1 + 311.7) * 43758.5453) % 1.0).let { if (it < 0) it + 1.0 else it }.toFloat() }

/** Скруглённый контур вокруг острова на расстоянии [d]: та же форма, только больше (у «Как вырез» — с прямым верхом). */
private fun DrawScope.outline(b: Rect, d: Float, color: Color, width: Float, notch: Boolean) {
    val corner = min(b.height / 2f, 46.dp.toPx())
    drawIslandRect(
        color,
        topLeft = Offset(b.left - d, b.top - d),
        size = Size(b.width + d * 2, b.height + d * 2),
        corner = corner + d,
        notch = notch,
        style = Stroke(width),
    )
}
