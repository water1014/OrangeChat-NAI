/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * 伴侣画布组件（对齐参考站 OVONovelAI 的自由画布 + dock 风格）
 * - CompanionHeroCard：中央角色卡（圆形头像 + 名称 + 副标题）
 * - RearrangeableFeatureWidgets：可丝滑重排的功能组件 ribbon（指令/主题/主动/世界书/记忆/对话回响）
 * - CompanionDock：把现有 ChatInput 包进玻璃质感停靠栏外壳
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 单个可重排功能组件的描述。
 * [navigate] 接收 Navigator，点击时跳转到对应真实页面（指令→提示词、世界书→注入、主动→主动消息等）。
 */
private data class CompanionWidget(
    val id: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val navigate: (Navigator) -> Unit,
)

/**
 * 默认功能组件列表。assistantId 用于需要绑定当前助手的页面（指令/世界书）。
 */
private fun defaultWidgets(assistantId: String): List<CompanionWidget> = listOf(
    CompanionWidget(
        id = "instructions",
        label = "指令",
        icon = HugeIcons.Edit03,
        navigate = { it.navigate(Screen.AssistantPrompt(id = assistantId)) },
    ),
    CompanionWidget(
        id = "theme",
        label = "主题",
        icon = HugeIcons.MagicWand01,
        navigate = { it.navigate(Screen.SettingTheme) },
    ),
    CompanionWidget(
        id = "proactive",
        label = "主动",
        icon = HugeIcons.Idea01,
        navigate = { it.navigate(Screen.SettingProactiveMessage) },
    ),
    CompanionWidget(
        id = "lorebook",
        label = "世界书",
        icon = HugeIcons.Book01,
        navigate = { it.navigate(Screen.AssistantInjections(id = assistantId)) },
    ),
    CompanionWidget(
        id = "memory",
        label = "记忆",
        icon = HugeIcons.Brain01,
        navigate = { it.navigate(Screen.MemoryBank) },
    ),
    CompanionWidget(
        id = "echo",
        label = "对话回响",
        icon = HugeIcons.Clock02,
        navigate = { it.navigate(Screen.History) },
    ),
)

/**
 * 中央角色卡：圆形头像 + 名称 + 副标题。对齐参考站「Aru / Start wherever you like.」。
 */
@Composable
fun CompanionHeroCard(
    assistant: Assistant,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val av = assistant.avatar) {
                    is Avatar.Image -> {
                        AsyncImage(
                            model = av.url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    is Avatar.Emoji -> {
                        Text(
                            text = av.content,
                            fontSize = 44.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    is Avatar.Dummy -> {
                        Text(
                            text = assistant.name
                                .ifBlank { "A" }
                                .firstOrNull()
                                ?.toString()
                                ?.uppercase()
                                ?: "A",
                            fontSize = 40.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Text(
            text = assistant.name.ifBlank { "助手" },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = "Start wherever you like.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 可丝滑重排的功能组件 ribbon。
 * 横向滚动排列；长按/拖动任意卡片即可与其他卡片交换位置，重排过程由 reorderable 自动做位移动画。
 * 点击卡片跳转到对应真实页面。
 */
@Composable
fun RearrangeableFeatureWidgets(
    navController: Navigator,
    assistantId: String,
    modifier: Modifier = Modifier,
) {
    var widgets by remember(assistantId) { mutableStateOf(defaultWidgets(assistantId)) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        widgets = widgets.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    LazyRow(
        state = lazyListState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = widgets, key = { it.id }) { widget ->
            ReorderableItem(
                state = reorderableState,
                key = widget.id,
            ) { isDragging ->
                val scale by animateFloatAsState(
                    targetValue = if (isDragging) 1.08f else 1f,
                    label = "widgetScale",
                )
                WidgetCard(
                    widget = widget,
                    isDragging = isDragging,
                    scale = scale,
                    onClick = { widget.navigate(navController) },
                    modifier = Modifier.longPressDraggableHandle(),
                )
            }
        }
    }
}

@Composable
private fun WidgetCard(
    widget: CompanionWidget,
    isDragging: Boolean,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.scale(scale),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
        tonalElevation = if (isDragging) 8.dp else 2.dp,
        shadowElevation = if (isDragging) 10.dp else 2.dp,
        border = if (isDragging) {
            BorderStroke(
                1.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = widget.icon,
                contentDescription = widget.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = widget.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 底部停靠栏（dock）外壳：玻璃质感圆角容器，内部承载现有 ChatInput，
 * 保留 ChatInput 的全部能力（模型选择/附件/语音/联网搜索/发送），只替换外观壳。
 */
@Composable
fun CompanionDock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}
