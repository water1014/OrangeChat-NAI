/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * 伴侣画布组件（对齐参考站的「沉浸式玻璃画布 + dock」风格）
 * - CompanionHeroCard：中央大玻璃角色卡（圆形头像 + 名称 + 副标题）
 * - DraggableGlassWidgets：散落在画布上、可自由丝滑拖动的玻璃组件
 *   （指令/主题/主动/世界书/记忆/对话回响 + 空对话时的日期卡/对话卡）
 * - CompanionDock：胶囊状玻璃停靠栏外壳，承载现有 ChatInput
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.context.Navigator

/** 玻璃底色：贴合参考站深色壁纸上的半透明毛玻璃质感。 */
@Composable
private fun glassTint(): Color =
    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)

/** 玻璃描边高光。 */
@Composable
private fun glassBorder(): Color = Color.White.copy(alpha = 0.20f)

/** 单个可拖动功能组件的描述。点击跳转到橘瓣里的真实页面。 */
private data class GlassWidget(
    val id: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val navigate: (Navigator) -> Unit,
)

private fun defaultGlassWidgets(assistantId: String): List<GlassWidget> = listOf(
    GlassWidget("instructions", "指令", HugeIcons.Edit03) {
        it.navigate(Screen.AssistantPrompt(id = assistantId))
    },
    GlassWidget("theme", "主题", HugeIcons.MagicWand01) {
        it.navigate(Screen.SettingTheme)
    },
    GlassWidget("proactive", "主动", HugeIcons.Idea01) {
        it.navigate(Screen.SettingProactiveMessage)
    },
    GlassWidget("lorebook", "世界书", HugeIcons.Book01) {
        it.navigate(Screen.AssistantInjections(id = assistantId))
    },
    GlassWidget("memory", "记忆", HugeIcons.Brain01) {
        it.navigate(Screen.MemoryBank)
    },
    GlassWidget("echo", "对话回响", HugeIcons.Clock02) {
        it.navigate(Screen.History)
    },
)

/**
 * 中央角色卡：横向大玻璃卡（圆形头像 + 名称），卡片下方是副标题。
 * [compact] 为 true 时（已有对话）渲染为顶部一行小胶囊，让画布感在对话中常驻。
 */
@Composable
fun CompanionHeroCard(
    assistant: Assistant,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (compact) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(28.dp),
            color = glassTint(),
            border = BorderStroke(1.dp, glassBorder()),
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeroAvatar(assistant, 34.dp)
                Text(
                    text = assistant.name.ifBlank { "助手" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(30.dp),
                color = glassTint(),
                border = BorderStroke(1.dp, glassBorder()),
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HeroAvatar(assistant, 92.dp)
                    Text(
                        text = assistant.name.ifBlank { "助手" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "Start wherever you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }
    }
}

/** 按 Avatar 三种类型渲染圆形头像。 */
@Composable
private fun HeroAvatar(assistant: Assistant, size: Dp) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val av = assistant.avatar) {
                is Avatar.Image -> AsyncImage(
                    model = av.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                is Avatar.Emoji -> Text(
                    text = av.content,
                    fontSize = (size.value * 0.45f).sp,
                    textAlign = TextAlign.Center,
                )

                is Avatar.Dummy -> Text(
                    text = assistant.name
                        .ifBlank { "A" }
                        .firstOrNull()
                        ?.toString()
                        ?.uppercase()
                        ?: "A",
                    fontSize = (size.value * 0.42f).sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 散落在画布上、可自由丝滑拖动的玻璃组件。
 *
 * - 拖动任意组件即可移动到画布任意位置（跟手位移 + 拖动时缩放/高亮）。
 * - [expanded] 为 true（空对话）时额外显示日期卡、对话卡与分页圆点，组件初始散落铺开，
 *   更贴近参考站主页；为 false（有对话）时收拢成两行网格，位于聊天列表上方。
 * - 点击功能组件跳转到对应真实页面。
 */
@Composable
fun DraggableGlassWidgets(
    navController: Navigator,
    assistantId: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widgets = remember(assistantId) { defaultGlassWidgets(assistantId) }
    val infoCount = if (expanded) 2 else 0
    val count = widgets.size + infoCount

    fun initialOffset(index: Int): Offset = with(density) {
        if (expanded) {
            when (index) {
                0 -> Offset(14.dp.toPx(), 16.dp.toPx())
                1 -> Offset(150.dp.toPx(), 92.dp.toPx())
                else -> {
                    val w = index - infoCount
                    val col = w % 3
                    val row = w / 3
                    val jitter = when (w) { 1 -> 14f; 4 -> -12f; else -> 0f }
                    Offset(
                        (12 + col * 112 + jitter).dp.toPx(),
                        (224 + row * 104).dp.toPx(),
                    )
                }
            }
        } else {
            val col = index % 3
            val row = index / 3
            Offset(
                (12 + col * 112).dp.toPx(),
                (10 + row * 82).dp.toPx(),
            )
        }
    }

    // 会话内记住每个组件的位置；切换对话/状态时归位
    val offsets = remember(expanded, assistantId) {
        mutableStateListOf<Offset>().apply {
            repeat(count) { add(initialOffset(it)) }
        }
    }
    var draggingIndex by remember(expanded, assistantId) { mutableStateOf(-1) }

    Box(modifier = modifier.fillMaxWidth()) {
        repeat(count) { index ->
            val isInfo = expanded && index < infoCount
            val widget = if (isInfo) null else widgets[index - infoCount]
            DraggableGlassItem(
                index = index,
                dragging = draggingIndex == index,
                offset = offsets[index],
                onDragStart = { draggingIndex = index },
                onDragEnd = { draggingIndex = -1 },
                onDrag = { delta -> offsets[index] = offsets[index] + delta },
            ) {
                if (isInfo) {
                    GlassInfoCard(index = index)
                } else if (widget != null) {
                    GlassWidgetChip(
                        widget = widget,
                        dragging = draggingIndex == index,
                        onClick = { widget.navigate(navController) },
                    )
                }
            }
        }

        if (expanded) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(5) { i ->
                    Surface(
                        modifier = Modifier.size(if (i == 0) 8.dp else 6.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = if (i == 0) 0.9f else 0.4f),
                    ) {}
                }
            }
        }
    }
}

/** 单个可拖动玻璃组件：跟手位移 + 拖动缩放动画。 */
@Composable
private fun DraggableGlassItem(
    index: Int,
    dragging: Boolean,
    offset: Offset,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.12f else 1f,
        label = "glassWidgetScale",
    )
    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .scale(scale)
            .pointerInput(index) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
    ) {
        content()
    }
}

/** 单个功能玻璃按钮（图标 + 文字）。 */
@Composable
private fun GlassWidgetChip(
    widget: GlassWidget,
    dragging: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = glassTint(),
        border = BorderStroke(
            if (dragging) 1.5.dp else 1.dp,
            if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else glassBorder(),
        ),
        tonalElevation = 0.dp,
        shadowElevation = if (dragging) 14.dp else 4.dp,
    ) {
        Column(
            modifier = Modifier
                .size(100.dp, 72.dp)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = widget.icon,
                contentDescription = widget.label,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = widget.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 空对话画布上的装饰性玻璃信息卡（日期卡 / 对话预览卡），同样可拖动。 */
@Composable
private fun GlassInfoCard(index: Int) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = glassTint(),
        border = BorderStroke(1.dp, glassBorder()),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .size(126.dp, 76.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (index == 0) {
                Text(
                    text = "星期四 · 八月廿八",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                )
                Text(
                    text = "2026.10.08",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = "对话 · 从这里开始",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = "把想说的话，交给这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                )
                Text(
                    text = "点点组件，随手摆放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 胶囊状玻璃停靠栏（dock）外壳：内部承载现有 ChatInput，只替换外观壳。 */
@Composable
fun CompanionDock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(32.dp),
        color = glassTint(),
        border = BorderStroke(1.dp, glassBorder()),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = 4.dp, vertical = 4.dp)),
        ) {
            content()
        }
    }
}
