package top.wkbin.taixu.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.feature.components.R

/**
 * 🎯 首次使用引导（Coach Mark）通用工具。
 *
 * 用法三步：
 * 1. `val anchor = rememberSpotlightAnchor()`，并给目标控件挂 `Modifier.spotlightAnchor(anchor)`；
 * 2. 首次进入时渲染 `SpotlightGuideOverlay(anchor = anchor, title = ..., message = ...)`
 *    （应与页面主布局同层渲染，保证与 [Modifier.spotlightAnchor] 坐标同参照系）；
 * 3. 点击蒙层或确认按钮回调 [onDismiss]，由调用方负责持久化"已看过"标记（如 DataStore）。
 */

/** 记录被高亮控件在根布局中的边界。 */
@Stable
class SpotlightAnchor {
    var bounds: Rect? by mutableStateOf<Rect?>(null)
        internal set
}

@Composable
fun rememberSpotlightAnchor(): SpotlightAnchor = remember { SpotlightAnchor() }

/** 挂在被高亮控件上，持续上报其边界供聚光灯开孔。 */
fun Modifier.spotlightAnchor(anchor: SpotlightAnchor): Modifier =
    onGloballyPositioned { anchor.bounds = it.boundsInRoot() }

/**
 * 聚光灯引导遮罩：半透明蒙层在目标控件处开圆角高亮孔（主题色描边），
 * 说明卡片自动放置在孔的下方；若孔位于屏幕下半部则自动改到上方，
 * 并根据控件所在左/右半屏决定卡片对齐方向。点击任意处或确认按钮关闭。
 *
 * 必须与页面主布局（如 Scaffold）在同一容器内作为兄弟节点渲染。
 */
@Composable
fun SpotlightGuideOverlay(
    anchor: SpotlightAnchor,
    title: String,
    message: String,
    confirmText: String? = null,
    icon: RuntimeIconName? = null,
    tooltipWidthFraction: Float = 0.8f,
    onDismiss: () -> Unit,
) {
    // 确认按钮文案参数化：默认从组件模块资源解析（values-en 同步"Got it"），
    // 调用点也可显式传入本地化文案覆盖默认值。
    val resolvedConfirmText = confirmText ?: stringResource(R.string.components_guide_confirm)
    val anchorBounds = anchor.bounds
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayOrigin = it.boundsInRoot().topLeft
                overlaySize = it.size
            }
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        // 首帧竞态：锚点控件可能尚未完成测量或遮罩自身 overlaySize 还是 0。
        // 若此时继续绘制，belowAnchor 会误判且 bottom padding 算出负值抛异常。
        // Box 必须常驻组合树以触发 onGloballyPositioned，内部元素等待自身及锚点坐标上报后再渲染。
        if (anchorBounds == null || overlaySize == IntSize.Zero) return@Box
        val accent = MaterialTheme.colorScheme.primary
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            val pad = 10.dp.toPx()
            val holePath = Path().apply {
                addRoundRect(
                    RoundRect(
                        anchorBounds.left - overlayOrigin.x - pad,
                        anchorBounds.top - overlayOrigin.y - pad,
                        anchorBounds.right - overlayOrigin.x + pad,
                        anchorBounds.bottom - overlayOrigin.y + pad,
                        CornerRadius(14.dp.toPx()),
                    ),
                )
            }
            drawPath(holePath, color = Color.Transparent, blendMode = BlendMode.Clear)
            drawPath(holePath, color = accent, style = Stroke(width = 2.dp.toPx()))
        }

        val density = LocalDensity.current
        val localAnchor = Rect(
            anchorBounds.left - overlayOrigin.x,
            anchorBounds.top - overlayOrigin.y,
            anchorBounds.right - overlayOrigin.x,
            anchorBounds.bottom - overlayOrigin.y,
        )
        val belowAnchor = localAnchor.center.y * 2 < overlaySize.height
        val anchorOnRight = localAnchor.center.x * 2 > overlaySize.width
        val gap = 14.dp

        Column(
            modifier = Modifier
                .align(
                    when {
                        belowAnchor && anchorOnRight -> Alignment.TopEnd
                        belowAnchor -> Alignment.TopStart
                        anchorOnRight -> Alignment.BottomEnd
                        else -> Alignment.BottomStart
                    },
                )
                .padding(
                    // 即使测量时序异常也绝不产生负 padding（Compose 会直接抛异常闪退）
                    top = (if (belowAnchor) with(density) { localAnchor.bottom.toDp() } + gap else 0.dp)
                        .coerceAtLeast(0.dp),
                    bottom = (if (!belowAnchor) with(density) { (overlaySize.height - localAnchor.top).toDp() } + gap else 0.dp)
                        .coerceAtLeast(0.dp),
                    start = if (!anchorOnRight) 16.dp else 0.dp,
                    end = if (anchorOnRight) 16.dp else 0.dp,
                )
                .fillMaxWidth(tooltipWidthFraction)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    RuntimeIcon(
                        name = icon,
                        modifier = Modifier.size(18.dp),
                        tint = accent,
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RuntimeTextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text(resolvedConfirmText, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
