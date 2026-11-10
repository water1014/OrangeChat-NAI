/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * 全新首页：沉浸式玻璃画布（大角色卡 + 散落可丝滑拖动组件 + 胶囊 dock）。
 * 这是一个独立于聊天界面的页面：点开对话 / 「开始聊天」才导航进 Screen.Chat。
 */

package me.rerere.rikkahub.ui.pages.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.context.LocalCurrentAssistant
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private fun glassTint(): Color = Color.White.copy(alpha = 0.10f)
private fun glassBorder(): Color = Color.White.copy(alpha = 0.22f)
private val onGlass: Color = Color.White
private val onGlassDim: Color = Color.White.copy(alpha = 0.65f)

private data class HomeWidget(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val navigate: (Navigator) -> Unit,
)

private fun homeWidgets(assistantId: String): List<HomeWidget> = listOf(
    HomeWidget("instructions", "指令", HugeIcons.Edit03) {
        it.navigate(Screen.AssistantPrompt(id = assistantId))
    },
    HomeWidget("theme", "主题", HugeIcons.MagicWand01) {
        it.navigate(Screen.SettingTheme)
    },
    HomeWidget("proactive", "主动", HugeIcons.Idea01) {
        it.navigate(Screen.SettingProactiveMessage)
    },
    HomeWidget("lorebook", "世界书", HugeIcons.Book01) {
        it.navigate(Screen.AssistantInjections(id = assistantId))
    },
    HomeWidget("memory", "记忆", HugeIcons.Brain01) {
        it.navigate(Screen.MemoryBank)
    },
    HomeWidget("echo", "对话回响", HugeIcons.Clock02) {
        it.navigate(Screen.History)
    },
)

/**
 * 全新首页（独立于聊天界面）。
 * 打开 App 首先看到这里；点角色卡 / 最近对话卡 / dock「开始聊天」才进入聊天界面。
 */
@Composable
fun CompanionHomePage() {
    val navigator = LocalNavController.current
    val assistant = LocalCurrentAssistant.current
    val repo = koinInject<ConversationRepository>()
    val conversations by remember(assistant.id) {
        repo.getConversationsOfAssistant(assistant.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    fun openChat(id: Uuid) {
        navigator.navigate(Screen.Chat(id = id.toString()))
    }

    fun newChat() {
        navigator.navigate(Screen.Chat(id = Uuid.random().toString()))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0E1120),
                        Color(0xFF1A1F35),
                        Color(0xFF2A2145),
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // 顶部标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "橘瓣",
                        style = MaterialTheme.typography.titleMedium,
                        color = onGlass,
                    )
                    Text(
                        text = "你的专属空间",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassDim,
                    )
                }
                TextButton(onClick = { navigator.navigate(Screen.Setting) }) {
                    Text(text = "设置", color = onGlass)
                }
            }

            // 中央大角色卡（点击开始新聊天）
            HomeHeroCard(assistant = assistant, onClick = { newChat() })

            // 散落、可丝滑拖动的玻璃组件画布
            HomeCanvas(
                navigator = navigator,
                assistantId = assistant.id.toString(),
                conversations = conversations,
                onOpenChat = { openChat(it) },
                onNewChat = { newChat() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // 底部胶囊 dock
            HomeDock(
                onNewChat = { newChat() },
                onHistory = { navigator.navigate(Screen.History) },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun HomeHeroCard(
    assistant: Assistant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(30.dp),
        color = glassTint(),
        border = BorderStroke(1.dp, glassBorder()),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HomeAvatar(assistant, 88.dp)
            Text(
                text = assistant.name.ifBlank { "助手" },
                style = MaterialTheme.typography.headlineSmall,
                color = onGlass,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Start wherever you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = onGlassDim,
                maxLines = 1,
            )
            Text(
                text = "轻点卡片，开始聊天",
                style = MaterialTheme.typography.labelSmall,
                color = onGlassDim,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HomeAvatar(assistant: Assistant, size: Dp) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, glassBorder()),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
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
                    color = onGlass,
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
                    color = onGlass,
                )
            }
        }
    }
}

@Composable
private fun HomeCanvas(
    navigator: Navigator,
    assistantId: String,
    conversations: List<Conversation>,
    onOpenChat: (Uuid) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widgets = remember(assistantId) { homeWidgets(assistantId) }
    val infoCount = 2
    val count = widgets.size + infoCount

    fun initialOffset(index: Int): Offset = with(density) {
        when (index) {
            0 -> Offset(14.dp.toPx(), 8.dp.toPx())
            1 -> Offset(154.dp.toPx(), 70.dp.toPx())
            else -> {
                val w = index - infoCount
                val col = w % 3
                val row = w / 3
                val jitter = when (w) {
                    1 -> 12f
                    4 -> -10f
                    else -> 0f
                }
                Offset(
                    (12 + col * 112 + jitter).dp.toPx(),
                    (188 + row * 96).dp.toPx(),
                )
            }
        }
    }

    val offsets = remember {
        mutableStateListOf<Offset>().apply {
            repeat(count) { add(initialOffset(it)) }
        }
    }
    var draggingIndex by remember { mutableStateOf(-1) }

    Box(modifier = modifier.fillMaxSize()) {
        repeat(count) { index ->
            DraggableItem(
                index = index,
                dragging = draggingIndex == index,
                offset = offsets[index],
                onDragStart = { draggingIndex = index },
                onDragEnd = { draggingIndex = -1 },
                onDrag = { delta -> offsets[index] = offsets[index] + delta },
            ) {
                when {
                    index == 0 -> DateGlassCard()
                    index == 1 -> ConversationGlassCard(
                        conversation = conversations.firstOrNull(),
                        onOpenChat = onOpenChat,
                        onNewChat = onNewChat,
                    )

                    else -> {
                        val w = widgets[index - infoCount]
                        GlassChip(
                            label = w.label,
                            icon = w.icon,
                            dragging = draggingIndex == index,
                            onClick = { w.navigate(navigator) },
                        )
                    }
                }
            }
        }

        // 分页小圆点
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
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

@Composable
private fun DraggableItem(
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
        label = "homeWidgetScale",
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

@Composable
private fun GlassChip(
    label: String,
    icon: ImageVector,
    dragging: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = glassTint(),
        border = BorderStroke(
            if (dragging) 1.5.dp else 1.dp,
            if (dragging) Color.White.copy(alpha = 0.8f) else glassBorder(),
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
                imageVector = icon,
                contentDescription = label,
                tint = onGlass,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = onGlassDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DateGlassCard() {
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
            Text(
                text = "今天 · 随心而动",
                style = MaterialTheme.typography.labelSmall,
                color = onGlassDim,
                maxLines = 1,
            )
            Text(
                text = "橘瓣",
                style = MaterialTheme.typography.titleMedium,
                color = onGlass,
                maxLines = 1,
            )
            Text(
                text = "把组件拖到喜欢的位置",
                style = MaterialTheme.typography.bodySmall,
                color = onGlassDim,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ConversationGlassCard(
    conversation: Conversation?,
    onOpenChat: (Uuid) -> Unit,
    onNewChat: () -> Unit,
) {
    Surface(
        onClick = { if (conversation != null) onOpenChat(conversation.id) else onNewChat() },
        shape = RoundedCornerShape(22.dp),
        color = glassTint(),
        border = BorderStroke(1.dp, glassBorder()),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .size(140.dp, 80.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "最近对话",
                style = MaterialTheme.typography.labelSmall,
                color = onGlassDim,
                maxLines = 1,
            )
            Text(
                text = conversation?.title?.ifBlank { "新对话" } ?: "开始第一段对话",
                style = MaterialTheme.typography.bodyMedium,
                color = onGlass,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "点此进入聊天 →",
                style = MaterialTheme.typography.bodySmall,
                color = onGlassDim,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HomeDock(
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(32.dp),
        color = glassTint(),
        border = BorderStroke(1.dp, glassBorder()),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onNewChat,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(text = "开始聊天", maxLines = 1)
            }
            FilledTonalButton(
                onClick = onHistory,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(text = "全部对话", maxLines = 1)
            }
        }
    }
}
