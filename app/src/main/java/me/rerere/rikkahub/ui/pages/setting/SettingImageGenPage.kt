/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import me.rerere.rikkahub.data.imggen.VibeJson
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.imggen.ArtistStyle
import me.rerere.rikkahub.data.imggen.CharacterLook
import me.rerere.rikkahub.data.imggen.ImageSize
import me.rerere.rikkahub.data.imggen.ImgChannel
import me.rerere.rikkahub.data.imggen.ImgGenPresetStore
import me.rerere.rikkahub.data.imggen.NaiParams
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.TopAppBar
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.common.android.appTempFolder
import java.io.File
import kotlin.uuid.Uuid

/**
 * 设置 → 生图。
 *
 * 这里是「唯一真源」：聊天里 AI 调用 generate_image、以及生图页面本身，都读同一份配置。
 * 面板里保留快捷入口，改完之后两边都会按这里的值走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingImageGenPage() {
    val context = LocalContext.current
    val store = remember { ImgGenPresetStore(context) }
    val scope = rememberCoroutineScope()

    var channels by remember { mutableStateOf(store.channels()) }
    var activeChannelId by remember { mutableStateOf(store.activeChannelId()) }
    var editingChannel by remember { mutableStateOf<ImgChannel?>(null) }

    var size by remember { mutableStateOf(store.imageSize()) }
    var count by remember { mutableIntStateOf(store.defaultCount()) }
    var negative by remember { mutableStateOf(store.negativePrompt()) }

    var artists by remember { mutableStateOf(store.artists()) }
    var activeArtistId by remember { mutableStateOf(store.activeArtist()?.id) }

    var characters by remember { mutableStateOf(store.characters()) }

    var references by remember { mutableStateOf(store.referenceImages()) }
    var strength by remember { mutableFloatStateOf(store.referenceStrength()) }
    var vibeJsons by remember { mutableStateOf(store.vibeJsons()) }
    var vibePresets by remember { mutableStateOf(store.vibePresets()) }
    var activeVibeId by remember { mutableStateOf(store.activeVibeId()) }
    var vibeName by remember { mutableStateOf("") }
    var showPasteVibe by remember { mutableStateOf(false) }
    var promptStyle by remember { mutableStateOf(store.promptStyle()) }
    var pasteText by remember { mutableStateOf("") }

    var nai by remember { mutableStateOf(store.naiParams()) }

    fun refreshChannels() {
        channels = store.channels()
        activeChannelId = store.activeChannelId()
    }

    val vibeJsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var ok = 0
        var failedHead = ""
        uris.forEach { uri ->
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readText()
                }.orEmpty()
            }.getOrDefault("")
            val parsed = me.rerere.rikkahub.data.imggen.VibeJsonParser.parse(text)
            if (parsed == null) {
                if (failedHead.isEmpty()) failedHead = text.take(60)
            } else {
                store.saveVibeJson(parsed)
                ok++
            }
        }
        vibeJsons = store.vibeJsons()
        android.widget.Toast.makeText(
            context,
            if (failedHead.isEmpty()) "已导入 $ok 个 vibe"
            else "成功 $ok 个，失败 ${uris.size - ok} 个；失败文件开头：$failedHead",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val paths = uris.mapNotNull { uri ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                            ?: error("decode failed")
                        val bytes = FileUtils.compressBitmapToPng(bitmap)
                        bitmap.recycle()
                        me.rerere.rikkahub.data.imggen.VibeFiles.save(context, bytes, "vibe").absolutePath
                    }.getOrNull()
                }
            }
            references = (references + paths).distinct().take(16)
            store.setReferenceImages(references)
        }
    }

    Scaffold(
        containerColor = settingsScaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text("生图设置") },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "AI 生图使用这套设置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "聊天里让 AI 画图时，会按这里的渠道、尺寸、画师串、角色外貌、负向词与 vibe 生成；" +
                                "AI 只负责写画面描述，不再自己拼这些参数。生图页操作的是同一份配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ------------------------------ 渠道 ------------------------------
            item {
                SettingsSection(title = "出图渠道", subtitle = "每套渠道自带协议、站点、Key、模型，AI 与面板都用当前选中的那套") {
                    if (channels.isEmpty()) {
                        Text(
                            text = "还没有渠道，先新建一个。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    channels.forEach { channel ->
                        val active = channel.id == activeChannelId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    activeChannelId = channel.id
                                    store.setActiveChannel(channel.id)
                                }
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${if (channel.protocol == "OPENAI") "OpenAI 兼容" else "NAI 原生"} · " +
                                        channel.modelId.ifBlank { "未填模型" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editingChannel = channel }) { Text("编辑") }
                            IconButton(
                                onClick = {
                                    store.deleteChannel(channel.id)
                                    refreshChannels()
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = "删除渠道",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            editingChannel = ImgChannel(
                                id = Uuid.random().toString(),
                                name = "",
                                protocol = "NOVELAI",
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("新建渠道")
                    }
                }
            }

            // ------------------------------ 输出 ------------------------------
            item {
                SettingsSection(title = "输出", subtitle = "AI 调用时没指定尺寸就用这里的值") {
                    val presets = listOf(
                        "1024×1024" to ImageSize(1024, 1024),
                        "832×1216" to ImageSize(832, 1216),
                        "1216×832" to ImageSize(1216, 832),
                        "1472×1472" to ImageSize(1472, 1472),
                        "1536×1024" to ImageSize(1536, 1024),
                        "1024×1536" to ImageSize(1024, 1536),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        presets.forEach { (label, preset) ->
                            FilterChip(
                                selected = size.width == preset.width && size.height == preset.height,
                                onClick = {
                                    size = preset
                                    store.setImageSize(preset)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }

                    var widthText by remember(size) { mutableStateOf(size.width.toString()) }
                    var heightText by remember(size) { mutableStateOf(size.height.toString()) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { widthText = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("宽") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("高") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val w = widthText.toIntOrNull() ?: size.width
                                val h = heightText.toIntOrNull() ?: size.height
                                val snapped = ImageSize(snap64(w), snap64(h))
                                size = snapped
                                store.setImageSize(snapped)
                            }
                        ) { Text("应用") }
                    }
                    Text(
                        text = "NAI 要求宽高是 64 的倍数、总像素 ≤1.5MP；gpt-image 只接受 1024×1024 / 1536×1024 / 1024×1536。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "默认张数",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                count = (count - 1).coerceAtLeast(1)
                                store.setDefaultCount(count)
                            },
                            enabled = count > 1,
                        ) { Text("−") }
                        Text("$count", style = MaterialTheme.typography.titleMedium)
                        TextButton(
                            onClick = {
                                count = (count + 1).coerceAtMost(4)
                                store.setDefaultCount(count)
                            },
                            enabled = count < 4,
                        ) { Text("+") }
                    }
                }
            }

            // ------------------------------ 提示词 ------------------------------
            item {
                SettingsSection(
                    title = "提示词",
                    subtitle = "正面预设词 = 画师串；负面预设词用于整个渠道。AI 调用前会自动拼上，AI 不需要重复写"
                ) {
                    Text(
                        text = "提示词风格",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "AUTO" to "自动",
                            "TAG" to "Danbooru 标签",
                            "NATURAL" to "自然语言",
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = promptStyle == value,
                                onClick = {
                                    promptStyle = value
                                    store.setPromptStyle(value)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                    Text(
                        text = "自动：NAI 渠道用 Danbooru 标签（更稳），gpt-image 这类用自然语言。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = "预设组",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = activeArtistId == null,
                            onClick = {
                                activeArtistId = null
                                store.setActiveArtist(null)
                            },
                            label = { Text("不使用") },
                            shape = RoundedCornerShape(10.dp),
                        )
                        artists.forEach { artist ->
                            FilterChip(
                                selected = activeArtistId == artist.id,
                                onClick = {
                                    activeArtistId = artist.id
                                    store.setActiveArtist(artist.id)
                                },
                                label = { Text(artist.name) },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }

                    // 当前预设的正面内容：直接编辑，保存回选中的预设组
                    var positiveDraft by remember(activeArtistId, artists) {
                        mutableStateOf(artists.firstOrNull { it.id == activeArtistId }?.value.orEmpty())
                    }
                    OutlinedTextField(
                        value = positiveDraft,
                        onValueChange = { positiveDraft = it },
                        label = { Text("正面预设词") },
                        placeholder = { Text("2:artist:bml94119, 1:artist:zero_q, very aesthetic") },
                        minLines = 4,
                        maxLines = 10,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (activeArtistId != null) {
                                    val updated = artists.map {
                                        if (it.id == activeArtistId) it.copy(value = positiveDraft.trim()) else it
                                    }
                                    artists = updated
                                    store.setArtists(updated)
                                } else {
                                    val item = ArtistStyle(
                                        id = Uuid.random().toString(),
                                        name = "预设 ${artists.size + 1}",
                                        value = positiveDraft.trim(),
                                    )
                                    artists = artists + item
                                    activeArtistId = item.id
                                    store.setArtists(artists)
                                    store.setActiveArtist(item.id)
                                }
                            },
                            enabled = positiveDraft.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(if (activeArtistId == null) "存为新预设" else "保存到当前预设") }

                        if (activeArtistId != null) {
                            TextButton(
                                onClick = {
                                    val updated = artists.filterNot { it.id == activeArtistId }
                                    artists = updated
                                    store.setArtists(updated)
                                    activeArtistId = null
                                    store.setActiveArtist(null)
                                    positiveDraft = ""
                                }
                            ) {
                                Text("删除此预设", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Text(
                        text = "画师串按权重写在最前面；换风格只改这里，不用逐条改正文。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = negative,
                        onValueChange = {
                            negative = it
                            store.setNegativePrompt(it)
                        },
                        label = { Text("负面预设词") },
                        placeholder = { Text("lowres, worst quality, bad quality, jpeg artifacts…") },
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (nai.enabled) {
                            "NAI 参数开启时，这段走 negative_prompt 字段；否则拼在正文尾部的 Negative prompt: 段。"
                        } else {
                            "会以 Negative prompt: 段落附在正文后面发出。AI 没传负向时用这里。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 常用负面词一键追加：负向是「鬼图」最直接的解药
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "通用崩坏" to "lowres, worst quality, bad quality, jpeg artifacts, watermark, signature, text, error, cropped",
                            "人体" to "bad anatomy, bad hands, extra fingers, fused fingers, poorly drawn face, extra limbs, missing limbs, deformed",
                            "画面" to "blurry, distorted perspective, warped lines, oversaturated, chromatic aberration",
                        ).forEach { (label, words) ->
                            FilterChip(
                                selected = negative.contains(words.take(12), ignoreCase = true),
                                onClick = {
                                    if (!negative.contains(words.take(12), ignoreCase = true)) {
                                        negative = if (negative.isBlank()) words else "$negative, $words"
                                        store.setNegativePrompt(negative)
                                    }
                                },
                                label = { Text("+ $label", style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                }
            }

            // ------------------------------ 角色 ------------------------------
            item {
                SettingsSection(title = "角色外貌", subtitle = "在提示词里写 @名字，生成前会自动替换成这里的外貌") {
                    characters.forEach { character ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(character.name, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = character.look,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                            TextButton(
                                onClick = {
                                    characters = characters.filterNot { it.id == character.id }
                                    store.setCharacters(characters)
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    var newName by remember { mutableStateOf("") }
                    var newLook by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("角色名") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newLook,
                        onValueChange = { newLook = it },
                        label = { Text("固定外貌（建议英文）") },
                        placeholder = { Text("silver hair, violet eyes, white shirt") },
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            if (newName.isBlank() || newLook.isBlank()) return@OutlinedButton
                            characters = characters + CharacterLook(
                                id = Uuid.random().toString(),
                                name = newName.trim(),
                                look = newLook.trim(),
                            )
                            store.setCharacters(characters)
                            newName = ""
                            newLook = ""
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("保存角色") }
                }
            }

            // ------------------------------ Vibe ------------------------------
            item {
                SettingsSection(
                    title = "Vibe 参考图（${references.size} 图 + ${vibeJsons.count { it.enabled }} JSON）",
                    subtitle = "AI 调用也会带上这批；NAI 渠道作为 vibe。合计上限 16，风格差异大的建议只开 1–2 个"
                ) {
                    Text("预设组", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = activeVibeId == null,
                            onClick = {
                                store.applyVibePreset(null)
                                activeVibeId = null
                            },
                            label = { Text("不使用") },
                            shape = RoundedCornerShape(10.dp),
                        )
                        vibePresets.forEach { preset ->
                            FilterChip(
                                selected = activeVibeId == preset.id,
                                onClick = {
                                    store.applyVibePreset(preset.id)
                                    references = store.referenceImages()
                                    strength = store.referenceStrength()
                                    activeVibeId = preset.id
                                },
                                label = { Text("${preset.name}（${preset.paths.size}）") },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = vibeName,
                            onValueChange = { vibeName = it },
                            label = { Text("新预设名称") },
                            placeholder = { Text("例如：水彩、某角色定妆") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (references.isEmpty()) return@TextButton
                                val item = store.saveVibePreset(vibeName)
                                vibePresets = store.vibePresets()
                                activeVibeId = item.id
                                vibeName = ""
                            },
                            enabled = references.isNotEmpty(),
                        ) { Text("存为预设") }
                        if (activeVibeId != null) {
                            TextButton(
                                onClick = {
                                    activeVibeId?.let { store.deleteVibePreset(it) }
                                    vibePresets = store.vibePresets()
                                    activeVibeId = null
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        references.forEach { path ->
                            Box {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                        .clickable {
                                            references = references.filterNot { it == path }
                                            store.setReferenceImages(references)
                                            runCatching { File(path).delete() }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = "移除",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                                .clickable { picker.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(HugeIcons.Add01, "添加参考图", modifier = Modifier.size(22.dp))
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "强度",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = String.format("%.2f", strength),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = strength,
                        onValueChange = {
                            strength = it
                            store.setReferenceStrength(it)
                        },
                        valueRange = 0.1f..1f,
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Text(
                        text = "Vibe 文件（.naiv4vibe / .json）",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "NAI 导出的 vibe 已经是编码好的数据，导入后直接随请求发出，不用再上传图片重新编码。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (vibeJsons.isEmpty()) {
                        Text(
                            text = "还没有导入 vibe 文件。选 .naiv4vibe / .json，或把文件内容粘贴进来。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    vibeJsons.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = buildString {
                                        append("强度 %.2f".format(item.strength))
                                        if (item.model.isNotBlank()) append(" · ${item.model}")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { on ->
                                    store.updateVibeJson(item.copy(enabled = on))
                                    vibeJsons = store.vibeJsons()
                                },
                            )
                            TextButton(
                                onClick = {
                                    val next = (item.strength - 0.05f).coerceIn(0.1f, 1f)
                                    store.updateVibeJson(item.copy(strength = next))
                                    vibeJsons = store.vibeJsons()
                                }
                            ) { Text("−") }
                            TextButton(
                                onClick = {
                                    val next = (item.strength + 0.05f).coerceIn(0.1f, 1f)
                                    store.updateVibeJson(item.copy(strength = next))
                                    vibeJsons = store.vibeJsons()
                                }
                            ) { Text("+") }
                            TextButton(
                                onClick = {
                                    store.deleteVibeJson(item.id)
                                    vibeJsons = store.vibeJsons()
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vibeJsonPicker.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("导入 Vibe JSON")
                        }
                        TextButton(onClick = { showPasteVibe = true }) {
                            Text("粘贴 JSON")
                        }
                    }
                }
            }

            // ------------------------------ NAI ------------------------------
            item {
                SettingsSection(title = "NAI 参数", subtitle = "仅 NAI 原生渠道生效，字段按 NovelAI 官方 API 命名") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用 NAI 参数", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "关掉则用接口默认值",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = nai.enabled,
                            onCheckedChange = {
                                nai = nai.copy(enabled = it)
                                store.setNaiParams(nai)
                            },
                        )
                    }

                    if (nai.enabled) {
                        NumberRow(
                            label = "采样步数 Steps",
                            value = nai.steps.toString(),
                            hint = "推荐 28，越高越细但耗时越长",
                            onChange = {
                                it.toIntOrNull()?.let { v ->
                                    nai = nai.copy(steps = v.coerceIn(1, 50))
                                    store.setNaiParams(nai)
                                }
                            },
                        )
                        NumberRow(
                            label = "CFG Scale",
                            value = nai.scale.toString(),
                            hint = "推荐 5，越高越贴提示词但画面越僵",
                            onChange = {
                                it.toFloatOrNull()?.let { v ->
                                    nai = nai.copy(scale = v.coerceIn(0f, 10f))
                                    store.setNaiParams(nai)
                                }
                            },
                        )
                        ChipRow(
                            label = "采样器",
                            options = listOf("Euler Ancestral", "Euler", "DPM++ 2M SDE", "DPM++ SDE"),
                            selected = nai.sampler,
                            hint = "NAI 常用 Euler Ancestral，想要更锐利可换 DPM++",
                            onSelect = {
                                nai = nai.copy(sampler = it)
                                store.setNaiParams(nai)
                            },
                        )
                        ChipRow(
                            label = "噪声调度",
                            options = listOf("Native", "Karras", "Exponential", "Polyexponential"),
                            selected = nai.noiseSchedule,
                            hint = "默认 Karras，多数场景最稳",
                            onSelect = {
                                nai = nai.copy(noiseSchedule = it)
                                store.setNaiParams(nai)
                            },
                        )
                        NumberRow(
                            label = "种子 Seed（-1 随机）",
                            value = nai.seed.toString(),
                            hint = "-1 表示随机；固定后同参数可复现同一张图",
                            onChange = {
                                it.toLongOrNull()?.let { v ->
                                    nai = nai.copy(seed = v)
                                    store.setNaiParams(nai)
                                }
                            },
                        )
                        ChipRow(
                            label = "负向预设 UC",
                            options = listOf("0 Heavy", "1 Light", "2 Human Focus", "3 None"),
                            hint = "官方负面预设，Heavy 覆盖最广",
                            selected = "${nai.ucPreset.coerceIn(0, 3)} " + when (nai.ucPreset.coerceIn(0, 3)) {
                                0 -> "Heavy"
                                1 -> "Light"
                                2 -> "Human Focus"
                                else -> "None"
                            },
                            onSelect = {
                                val index = it.substringBefore(" ").toIntOrNull() ?: 0
                                nai = nai.copy(ucPreset = index.coerceIn(0, 3))
                                store.setNaiParams(nai)
                            },
                        )
                        NumberRow(
                            label = "CFG Rescale",
                            value = nai.cfgRescale.toString(),
                            hint = "0–1，降低 CFG 的过度饱和（推荐 0）",
                            onChange = {
                                it.toFloatOrNull()?.let { v ->
                                    nai = nai.copy(cfgRescale = v.coerceIn(0f, 1f))
                                    store.setNaiParams(nai)
                                }
                            },
                        )
                        NumberRow(
                            label = "负向强度 Undesired Content",
                            value = nai.ucStrength.toString(),
                            hint = "负面提示词强度（推荐 1）",
                            onChange = {
                                it.toFloatOrNull()?.let { v ->
                                    nai = nai.copy(ucStrength = v.coerceIn(0f, 2f))
                                    store.setNaiParams(nai)
                                }
                            },
                        )
                        SwitchRow("Quality Tags", "V4.5 自动在提示词前加官方质量词（best quality, very aesthetic, absurdres）", nai.qualityTags) {
                            nai = nai.copy(qualityTags = it)
                            store.setNaiParams(nai)
                        }
                        SwitchRow("SMEA", "VAE 平滑，减少细节噪点", nai.smea) {
                            nai = nai.copy(smea = it)
                            store.setNaiParams(nai)
                        }
                        SwitchRow("SMEA DYN", "动态 SMEA，与 SMEA 一起开更稳", nai.smeaDyn) {
                            nai = nai.copy(smeaDyn = it)
                            store.setNaiParams(nai)
                        }
                        SwitchRow("Variety+", "提升画面多样性、减少同质化（V4 系列有效）", nai.varietyBoost) {
                            nai = nai.copy(varietyBoost = it)
                            store.setNaiParams(nai)
                        }
                        SwitchRow("Dynamic Thresholding", "动态阈值，高 CFG 时抑制过曝", nai.dynamicThresholding) {
                            nai = nai.copy(dynamicThresholding = it)
                            store.setNaiParams(nai)
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextButton(
                        onClick = {
                            val defaultSize = ImageSize(1024, 1024)
                            size = defaultSize
                            store.setImageSize(defaultSize)
                            count = 1
                            store.setDefaultCount(1)
                            negative = ""
                            store.setNegativePrompt("")
                            nai = NaiParams()
                            store.setNaiParams(nai)
                        }
                    ) {
                        Text("恢复默认输出与参数")
                    }
                    Text(
                        text = "所有改动即时保存，AI 调用与生图页立即生效。恢复默认不会删除渠道、预设组与角色。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    if (showPasteVibe) {
        AlertDialog(
            onDismissRequest = { showPasteVibe = false },
            title = { Text("粘贴 Vibe JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "把 .naiv4vibe 文件里的内容整段粘进来，需要包含 encoded 字段。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        minLines = 6,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = me.rerere.rikkahub.data.imggen.VibeJsonParser.parse(pasteText)
                        if (parsed == null) {
                            android.widget.Toast.makeText(
                                context,
                                "解析失败，内容开头是：${pasteText.take(60)}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            store.saveVibeJson(parsed)
                            vibeJsons = store.vibeJsons()
                            pasteText = ""
                            showPasteVibe = false
                            android.widget.Toast.makeText(context, "已导入：${parsed.name}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = pasteText.isNotBlank(),
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteVibe = false }) { Text("取消") }
            },
        )
    }

    editingChannel?.let { channel ->
        ChannelDialog(
            initial = channel,
            onDismiss = { editingChannel = null },
            onSave = {
                store.updateChannel(it)
                refreshChannels()
                editingChannel = null
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun NumberRow(
    label: String,
    value: String,
    hint: String? = null,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    hint: String? = null,
    onSelect: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    if (hint != null) {
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(10.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChannelDialog(
    initial: ImgChannel,
    onDismiss: () -> Unit,
    onSave: (ImgChannel) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var modelId by remember { mutableStateOf(initial.modelId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "新建渠道" else "编辑渠道") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NOVELAI" to "NAI 原生", "OPENAI" to "OpenAI 兼容").forEach { (value, label) ->
                        FilterChip(
                            selected = protocol == value,
                            onClick = { protocol = value },
                            label = { Text(label) },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("站点地址") },
                    placeholder = { Text("https://api.novelai.net") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 ID") },
                    placeholder = { Text("nai-diffusion-4-5-full") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (protocol == "NOVELAI")
                        "NAI 原生请求 {站点}/ai/generate-image，站点地址不要带 /v1"
                    else
                        "OpenAI 兼容请求 {站点}/images/generations，站点地址通常以 /v1 结尾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { "未命名渠道" },
                            protocol = protocol,
                            baseUrl = baseUrl.trim().trimEnd('/'),
                            apiKey = apiKey.trim(),
                            modelId = modelId.trim(),
                        )
                    )
                },
                enabled = baseUrl.isNotBlank() && modelId.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun snap64(value: Int): Int {
    val clamped = value.coerceIn(64, 2048)
    return ((clamped / 64f).toInt() * 64).coerceIn(64, 2048)
}

/**
 * 解析 NAI 的 vibe 文件。
 * 兼容 {encoded}, {vibe:{encoded}}, {data} 三种结构；强度取 information_extracted。
 */
