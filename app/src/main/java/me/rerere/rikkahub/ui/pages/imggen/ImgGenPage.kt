/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.imggen

import me.rerere.rikkahub.ui.pages.setting.settingsScaffoldContainerColor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Colors
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.imggen.ArtistStyle
import me.rerere.rikkahub.data.imggen.CharacterLook
import me.rerere.rikkahub.data.imggen.ImageSize
import me.rerere.rikkahub.data.imggen.ImgChannel
import me.rerere.rikkahub.data.imggen.NaiParams
import me.rerere.rikkahub.data.imggen.PromptPreset
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.TopAppBar
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ImageGenPage(
    modifier: Modifier = Modifier,
    vm: ImgGenVM = koinViewModel()
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    BackHandler(isGenerating) {
        showCancelDialog = true
    }
    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            }
        )
    }

    Scaffold(
        containerColor = settingsScaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.imggen_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    val nav = me.rerere.rikkahub.ui.context.LocalNavController.current
                    IconButton(onClick = { nav.navigate(me.rerere.rikkahub.Screen.SettingImageGen) }) {
                        Icon(
                            imageVector = HugeIcons.Tools,
                            contentDescription = "生图设置"
                        )
                    }
                    IconButton(onClick = vm::startNewSession) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = "New session"
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomBar(pagerState, scope)
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> ImageGenScreen(vm = vm)
                1 -> ImageGalleryScreen(vm = vm)
            }
        }
    }
}

@Composable
private fun CancelDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_cancel_generation_title)) },
        text = { Text(stringResource(R.string.imggen_page_cancel_generation_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.imggen_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_cancel))
            }
        }
    )
}

@Composable
private fun BottomBar(
    pagerState: PagerState,
    scope: CoroutineScope
) {
    NavigationBar {
        NavigationBarItem(
            selected = 0 == pagerState.currentPage,
            label = { Text("生图") },
            icon = { Icon(HugeIcons.Colors, null) },
            onClick = {
                scope.launch { pagerState.animateScrollToPage(0) }
            }
        )

        NavigationBarItem(
            selected = 1 == pagerState.currentPage,
            label = { Text(stringResource(R.string.imggen_page_gallery)) },
            icon = { Icon(HugeIcons.Image03, null) },
            onClick = {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
        )
    }
}

/* ---------------------------------------------------------------------------------------------
 * 主面板：上方结果画布 + 下方参数/输入区，参考 NAI 面板的信息层级，观感沿用橘瓣的圆角与玻璃表面
 * -------------------------------------------------------------------------------------------*/

@Composable
private fun ImageGenScreen(
    vm: ImgGenVM,
) {
    // 从「设置 → 生图」返回时重新读配置，保证两边一致
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.reloadFromStore()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val negativePrompt by vm.negativePrompt.collectAsStateWithLifecycle()
    val numberOfImages by vm.numberOfImages.collectAsStateWithLifecycle()
    val aspectRatio by vm.aspectRatio.collectAsStateWithLifecycle()
    val imageSize by vm.imageSize.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val currentGeneratedImages by vm.currentGeneratedImages.collectAsStateWithLifecycle()
    val referenceImages by vm.referenceImages.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val characters by vm.characters.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val nai by vm.naiParams.collectAsStateWithLifecycle()
    val lastSeed by vm.lastSeed.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val activeChannelId by vm.activeChannelId.collectAsStateWithLifecycle()
    var showChannelSheet by remember { mutableStateOf(false) }
    var editingChannel by remember { mutableStateOf<ImgChannel?>(null) }
    val activeArtistId by vm.activeArtistId.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val imageModelId = remember(settings.imageGenerationModelId) {
        settings.findModelById(settings.imageGenerationModelId)?.modelId.orEmpty()
    }
    val hasImageModel = remember(settings.providers) {
        settings.providers.any { provider ->
            provider.enabled && provider.models.any { it.type == ModelType.IMAGE }
        }
    }
    val isNaiProtocol = remember(settings.imageGenerationModelId, settings.providers) {
        val target = settings.findModelById(settings.imageGenerationModelId)
        val provider = target?.let { m ->
            settings.providers.firstOrNull { p -> p.models.any { it.id == m.id } }
        }
        (provider as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.imageProtocol ==
            me.rerere.ai.provider.ImageProtocol.NOVELAI
    }
    val imageProviderHint = remember(settings.imageGenerationModelId, settings.providers) {
        val target = settings.findModelById(settings.imageGenerationModelId)
        val provider = target?.let { m ->
            settings.providers.firstOrNull { p -> p.models.any { it.id == m.id } }
        }
        val baseUrl = (provider as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.baseUrl.orEmpty()
        "${provider?.name.orEmpty()} $baseUrl"
    }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSizeSheet by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(error) {
        error?.let { errorMessage ->
            toaster.show(message = errorMessage, type = ToastType.Error)
            vm.clearError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .imePadding(),
    ) {
        PanelTabs(selected = tab, onSelect = { tab = it })

        when (tab) {
            0 -> Column(modifier = Modifier.weight(1f)) {
                ChannelBar(
                    channels = channels,
                    activeChannelId = activeChannelId,
                    onOpen = { showChannelSheet = true },
                )

                if (!hasImageModel && channels.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            text = "还没有可用的「图片模型」：设置 → 提供商 → 模型，把 nai / gpt-image 那个模型的类型改成「图片模型」，否则这里选不到它。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }

                ResultCanvas(
                    images = currentGeneratedImages.map { it.filePath },
                    prompt = prompt,
                    ratio = imageSize.ratio,
                    sizeLabel = imageSize.label,
                    isGenerating = isGenerating,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    onPickIdea = vm::updatePrompt,
                )

                Spacer(modifier = Modifier.height(10.dp))

                QuickParamsRow(
                    size = imageSize,
                    numberOfImages = numberOfImages,
                    lastSeed = lastSeed,
                    lockedSeed = nai.seed.takeIf { nai.enabled && it >= 0 },
                    onOpenSize = { showSizeSheet = true },
                    onCountChange = vm::updateNumberOfImages,
                    onLockSeed = {
                        val seed = vm.lockLastSeed()
                        if (seed != null) {
                            toaster.show(message = "已锁定种子 $seed，下次按同参数出图", type = ToastType.Success)
                        }
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Composer(
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    referenceImages = referenceImages,
                    settings = settings,
                    isGenerating = isGenerating,
                    onPromptChange = vm::updatePrompt,
                    onNegativeChange = vm::updateNegativePrompt,
                    onShowSettings = { showSettingsSheet = true },
                    onGenerate = {
                        // NAI 原生协议下参考图是 vibe，仍然走文生图接口
                        if (referenceImages.isEmpty() || isNaiProtocol) vm.generateImage() else vm.editImage()
                    },
                    onCancel = vm::cancelGeneration,
                    onAddReference = vm::addReferenceImages,
                    onRemoveReference = vm::removeReferenceImage,
                    jsonVibeCount = vm.vibeJsonCount(),
                    onModelChange = { modelId ->
                        scope.launch {
                            vm.settingsStore.update { old ->
                                old.copy(imageGenerationModelId = Uuid.parse(modelId))
                            }
                        }
                    },
                )

                Spacer(modifier = Modifier.height(10.dp))
            }

            1 -> PresetsTab(
                vm = vm,
                currentPrompt = prompt,
                currentNegative = negativePrompt,
                presets = presets,
                artists = artists,
                activeArtistId = activeArtistId,
                modifier = Modifier.weight(1f),
            )

            2 -> CharactersTab(
                vm = vm,
                characters = characters,
                modifier = Modifier.weight(1f),
            )

            3 -> ReferenceTab(
                vm = vm,
                referenceImages = referenceImages,
                isNaiProtocol = isNaiProtocol,
                modifier = Modifier.weight(1f),
            )

            else -> NaiTab(
                vm = vm,
                nai = nai,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showChannelSheet) {
        ChannelSheet(
            channels = channels,
            activeChannelId = activeChannelId,
            onSelect = { id -> vm.setActiveChannel(id); showChannelSheet = false },
            onCreate = { editingChannel = ImgChannel(id = java.util.UUID.randomUUID().toString(), name = "") },
            onEdit = { editingChannel = it },
            onDelete = { vm.deleteChannel(it) },
            onDismiss = { showChannelSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }

    editingChannel?.let { channel ->
        ChannelEditorDialog(
            initial = channel,
            onDismiss = { editingChannel = null },
            onSave = {
                vm.saveChannel(it)
                editingChannel = null
            },
        )
    }

    if (showSizeSheet) {
        SizePickerSheet(
            current = imageSize,
            modelId = imageModelId,
            providerHint = imageProviderHint,
            onSelect = { w, h -> vm.updateSize(w, h) },
            onDismiss = { showSizeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            vm = vm,
            settings = settings,
            numberOfImages = numberOfImages,
            aspectRatio = aspectRatio,
            scope = scope,
            sheetState = sheetState,
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun PanelTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val titles = listOf("生图", "预设", "角色", "参考", "NAI")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        titles.forEachIndexed { index, title ->
            val active = selected == index
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(14.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
                },
                border = BorderStroke(
                    1.dp,
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
                ),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 预设：正负向提示词存下来，带使用次数，常用的会自动排前面 */
@Composable
private fun PresetsTab(
    vm: ImgGenVM,
    currentPrompt: String,
    currentNegative: String,
    presets: List<PromptPreset>,
    artists: List<ArtistStyle>,
    activeArtistId: String?,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var positive by remember { mutableStateOf(currentPrompt) }
    var negative by remember { mutableStateOf(currentNegative) }

    LaunchedEffect(currentPrompt) { if (positive.isBlank()) positive = currentPrompt }
    LaunchedEffect(currentNegative) { if (negative.isBlank()) negative = currentNegative }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArtistSection(
            vm = vm,
            artists = artists,
            activeArtistId = activeArtistId,
        )

        GlassCard {
            Text("新建预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = positive,
                onValueChange = { positive = it },
                label = { Text("正向提示词") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = negative,
                onValueChange = { negative = it },
                label = { Text("负向提示词") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = {
                        vm.savePreset(name, positive, negative)
                        name = ""
                    },
                    enabled = positive.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "存为预设",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                Surface(
                    onClick = {
                        positive = currentPrompt
                        negative = currentNegative
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "取当前输入",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (presets.isEmpty()) {
            Text(
                text = "还没有预设。写一条存下来，下次点「使用」就能一键套用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        presets.sortedByDescending { it.uses }.forEach { preset ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "已用 ${preset.uses} 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.bumpPreset(preset.id, 1) }, modifier = Modifier.size(30.dp)) {
                        Text("+", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { vm.bumpPreset(preset.id, -1) }, modifier = Modifier.size(30.dp)) {
                        Text("−", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Text(
                    text = preset.positive,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                if (preset.negative.isNotBlank()) {
                    Text(
                        text = "负向：${preset.negative}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.applyPreset(preset) }) { Text("使用") }
                    TextButton(onClick = { vm.deletePreset(preset.id) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 角色：名字 + 固定外貌，插入提示词时拼在当前描述后面 */
@Composable
private fun CharactersTab(
    vm: ImgGenVM,
    characters: List<CharacterLook>,
    modifier: Modifier = Modifier,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var look by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard {
            Text("角色外貌绑定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "用英文写固定外貌（发色/瞳色/脸型/服饰）。保存后，提示词里只要写 @角色名，生成前会自动替换成这段外貌——比每次重写更稳",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (characters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    characters.forEach { character ->
                        FilterChip(
                            selected = selectedId == character.id,
                            onClick = {
                                selectedId = character.id
                                name = character.name
                                look = character.look
                            },
                            label = { Text(character.name) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("角色名") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = look,
                onValueChange = { look = it },
                label = { Text("固定外貌") },
                placeholder = { Text("silver hair, violet eyes, pale skin, white shirt…") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = { vm.saveCharacter(name, look) },
                    enabled = name.isNotBlank() && look.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "保存外貌",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                Surface(
                    onClick = {
                        characters.firstOrNull { it.id == selectedId }?.let { vm.applyCharacter(it) }
                    },
                    enabled = selectedId != null,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "插入提示词",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                Surface(
                    onClick = {
                        selectedId = null
                        name = ""
                        look = ""
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "清空绑定",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        characters.forEach { character ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(character.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = character.look,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    TextButton(onClick = { vm.applyCharacter(character) }) { Text("插入") }
                    TextButton(onClick = { vm.deleteCharacter(character.id) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 参考图：图生图的入口，强度/噪声由模型或中转决定，这里只收图并说明 */
@Composable
private fun ReferenceTab(
    vm: ImgGenVM,
    referenceImages: List<String>,
    isNaiProtocol: Boolean,
    modifier: Modifier = Modifier,
) {
    val strength by vm.referenceStrength.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    // 参考页也能直接导入 .naiv4vibe / .json，不用绕到设置里
    val vibeJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var ok = 0
                uris.forEach { uri ->
                    val text = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull().orEmpty()
                    }
                    val parsed = me.rerere.rikkahub.data.imggen.VibeJsonParser.parse(text)
                    if (parsed != null) {
                        vm.importVibeJson(parsed)
                        ok++
                    }
                }
                if (ok == 0) {
                    toaster.show(message = "不是有效的 vibe 文件", type = ToastType.Error)
                } else {
                    toaster.show(message = "已导入 $ok 个 vibe", type = ToastType.Success)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ReferencePicker(
            referenceImages = referenceImages,
            onAdd = vm::addReferenceImages,
            onRemove = vm::removeReferenceImage,
            onImportJson = { vibeJsonLauncher.launch(arrayOf("*/*")) },
            isNaiProtocol = isNaiProtocol,
            strength = strength,
            onStrengthChange = vm::updateReferenceStrength,
            vibeJsonCount = vm.vibeJsonCount(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReferencePicker(
    referenceImages: List<String>,
    onAdd: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onImportJson: () -> Unit = {},
    isNaiProtocol: Boolean = false,
    strength: Float = 0.6f,
    onStrengthChange: (Float) -> Unit = {},
    vibeJsonCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val paths = uris.mapNotNull { uri ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                                ?: error("Failed to decode image")
                            val pngBytes = FileUtils.compressBitmapToPng(bitmap)
                            bitmap.recycle()
                            me.rerere.rikkahub.data.imggen.VibeFiles.save(context, pngBytes, "ref").absolutePath
                        }.getOrNull()
                    }
                }
                onAdd(paths)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard {
            Text("参考图 / 图生图", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = buildString {
                    append("加入参考图后：NAI 渠道下这批图会作为 vibe 参与生成，其它渠道走图生图（编辑）通道。最多 16 张。")
                    append("\n在聊天里让 AI 画图时，也会自动带上这里同一批图和同一个强度。")
                    if (vibeJsonCount > 0) {
                        append("\n已启用 $vibeJsonCount 个 Vibe JSON，会与上面的图片一起生效（在设置 → 生图设置 里管理）。")
                        if (vibeJsonCount + referenceImages.size > 2) {
                            append("\n同时启用多个风格差异大的 vibe 会互相削弱，建议 1–2 个。")
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { launcher.launch("image/*") },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "+ 参考图",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
                Surface(
                    onClick = onImportJson,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "导入 Vibe JSON",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
            }

            if (referenceImages.isNotEmpty()) {
                ReferenceImagesRow(images = referenceImages, onRemove = onRemove)
            }

            if (isNaiProtocol) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Vibe 强度",
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
                        onValueChange = onStrengthChange,
                        valueRange = 0.1f..1f,
                    )
                    Text(
                        text = "NAI 会先把参考图经 /ai/encode-vibe 编码，再作为 vibe 参与生成；强度越高越贴近参考图",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            } else {
                Text(
                    text = "强度 / 噪声这类参数不在本机暴露：不同中转接口字段不一样，可在「模型 → 自定义请求体」里按服务商文档补。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}


/** 结果画布：空态 / 生成中 / 结果网格 三态，外面套一层带描边的玻璃表面 */
@Composable
private fun ResultCanvas(
    images: List<String>,
    prompt: String,
    ratio: Float,
    sizeLabel: String,
    isGenerating: Boolean,
    onPickIdea: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isGenerating && images.isEmpty() -> GeneratingState(prompt = prompt)

                images.isEmpty() -> EmptyState(
                    sizeLabel = sizeLabel,
                    onPickIdea = onPickIdea,
                )

                else -> ResultGrid(
                    images = images,
                    ratio = ratio,
                    isGenerating = isGenerating,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    sizeLabel: String,
    onPickIdea: (String) -> Unit,
) {
    val ideas = remember {
        listOf(
            "赛博朋克雨夜的街角，霓虹倒映在积水里，电影感",
            "一只橘猫趴在窗台晒太阳，胶片颗粒，暖色调",
            "极简主义海报，橙色渐变几何构成，留白",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Colors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "描述你想要的画面",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "写清楚主体、风格和光线，比堆关键词更容易出好图",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(20.dp))

        ideas.forEach { idea ->
            Surface(
                onClick = { onPickIdea(idea) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            ) {
                Text(
                    text = idea,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    maxLines = 2,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "输出尺寸 $sizeLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun GeneratingState(prompt: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularWavyProgressIndicator(
            modifier = Modifier.size(56.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "正在生成…",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (prompt.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun ResultGrid(
    images: List<String>,
    ratio: Float,
    isGenerating: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { path ->
                    var showPreview by remember(path) { mutableStateOf(false) }
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { showPreview = true },
                        contentScale = ContentScale.Crop,
                    )
                    if (showPreview) {
                        ImagePreviewDialog(
                            images = listOf(path),
                            onDismissRequest = { showPreview = false },
                        )
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        if (isGenerating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "还在生成，稍等…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 尺寸 + 张数：尺寸直接决定请求体里的 size，NAI 与 GPT-image 的取值不同 */
@Composable
private fun QuickParamsRow(
    size: ImageSize,
    numberOfImages: Int,
    lastSeed: Long?,
    lockedSeed: Long?,
    onOpenSize: () -> Unit,
    onCountChange: (Int) -> Unit,
    onLockSeed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onOpenSize,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = size.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "尺寸 ▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.62f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 2.dp),
            ) {
                IconButton(
                    onClick = { onCountChange(numberOfImages - 1) },
                    enabled = numberOfImages > 1,
                    modifier = Modifier.size(32.dp),
                ) {
                    Text("−", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "${numberOfImages} 张",
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(
                    onClick = { onCountChange(numberOfImages + 1) },
                    enabled = numberOfImages < 4,
                    modifier = Modifier.size(32.dp),
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (lastSeed != null) {
            val locked = lockedSeed == lastSeed
            Surface(
                onClick = onLockSeed,
                shape = RoundedCornerShape(14.dp),
                color = if (locked) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.62f),
                border = BorderStroke(
                    1.dp,
                    if (locked) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                ),
            ) {
                Text(
                    text = if (locked) "种子 $lastSeed 已锁" else "种子 $lastSeed ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (locked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** 尺寸选择：给 NAI / GPT-image 常用值，也能手填（自动对齐到 64 的倍数） */
@Composable
private fun SizePickerSheet(
    current: ImageSize,
    modelId: String,
    providerHint: String,
    onSelect: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
) {
    // 尺寸规范优先按模型 ID 判断；ID 被改得认不出时，用提供商名称/域名兜底，最后可以手动指定
    val haystack = "$modelId $providerHint".lowercase()
    val autoIsNai = Regex("(^|[-_/ ])nai|novelai|diffusion").containsMatchIn(haystack)
    val autoIsGpt = haystack.contains("gpt-image") || haystack.contains("dall")
    var family by remember { mutableStateOf(if (autoIsNai) "nai" else if (autoIsGpt) "gpt" else "auto") }
    val isNai = family == "nai" || (family == "auto" && autoIsNai)
    val isGpt = family == "gpt" || (family == "auto" && !autoIsNai && autoIsGpt)
    val presets = remember(modelId) {
        val nai = listOf(
            Triple("832×1216 · 竖", 832, 1216),
            Triple("1216×832 · 横", 1216, 832),
            Triple("1024×1024 · 方", 1024, 1024),
            Triple("1472×1472 · 大方形", 1472, 1472),
            Triple("1536×640 · 壁纸横", 1536, 640),
            Triple("2048×1024 · 宽幅", 2048, 1024),
        )
        val gpt = listOf(
            Triple("1024×1024 · 方", 1024, 1024),
            Triple("1536×1024 · 横", 1536, 1024),
            Triple("1024×1536 · 竖", 1024, 1536),
        )
        when {
            isNai -> nai
            isGpt -> gpt
            else -> nai + gpt
        }
    }
    var width by remember { mutableStateOf(current.width.toString()) }
    var height by remember { mutableStateOf(current.height.toString()) }
    val pixels = (width.toIntOrNull() ?: 0) * (height.toIntOrNull() ?: 0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "输出尺寸",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                listOf("auto" to "自动", "nai" to "NAI", "gpt" to "GPT").forEach { (key, label) ->
                    FilterChip(
                        selected = family == key,
                        onClick = { family = key },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
            Text(
                text = when {
                    isNai -> "当前是 NAI 系模型：宽高必须是 64 的倍数，总像素不超过 1.5MP。"
                    isGpt -> "当前是 gpt-image 模型：只接受 1024×1024、1536×1024、1024×1536 三档。"
                    else -> "NAI 要求宽高是 64 的倍数、总像素 ≤1.5MP；gpt-image 只接受 1024×1024 / 1536×1024 / 1024×1536。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            presets.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, w, h) ->
                        val selected = current.width == w && current.height == h
                        Surface(
                            onClick = {
                                onSelect(w, h)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            ),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            )
                        }
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("宽") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("高") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            val gptAllowed = listOf(1024 to 1024, 1536 to 1024, 1024 to 1536)
            val gptOK = gptAllowed.any { it.first == (width.toIntOrNull() ?: 0) && it.second == (height.toIntOrNull() ?: 0) }
            Text(
                text = when {
                    isGpt && !gptOK -> "gpt-image 不接受这个尺寸，请用上面三档之一"
                    pixels > 1_500_000 -> "当前 ${pixels / 1000}K 像素，超过 NAI 上限 1.5MP，可能被拒"
                    else -> "当前 ${pixels / 1000}K 像素 · 实际会按 64 取整"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if ((isGpt && !gptOK) || pixels > 1_500_000) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                onClick = {
                    val w = width.toIntOrNull() ?: current.width
                    val h = height.toIntOrNull() ?: current.height
                    onSelect(w, h)
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "应用自定义尺寸",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}


@Composable
private fun Composer(
    prompt: String,
    negativePrompt: String,
    referenceImages: List<String>,
    settings: Settings,
    isGenerating: Boolean,
    onPromptChange: (String) -> Unit,
    onNegativeChange: (String) -> Unit,
    onShowSettings: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onAddReference: (List<String>) -> Unit,
    onRemoveReference: (String) -> Unit,
    jsonVibeCount: Int = 0,
    onModelChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    val paths = selectedUris.mapNotNull { uri ->
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                                    ?: error("Failed to decode image")
                                val pngBytes = FileUtils.compressBitmapToPng(bitmap)
                                bitmap.recycle()
                                me.rerere.rikkahub.data.imggen.VibeFiles.save(context, pngBytes, "ref").absolutePath
                            }.getOrNull()
                        }
                    }
                    onAddReference(paths)
                }
            }
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (referenceImages.isNotEmpty()) {
                ReferenceImagesRow(
                    images = referenceImages,
                    onRemove = onRemoveReference,
                )
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.imggen_page_prompt_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 150.dp),
                minLines = 1,
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )

            var showNegative by remember { mutableStateOf(negativePrompt.isNotBlank()) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    onClick = { showNegative = !showNegative },
                    shape = RoundedCornerShape(10.dp),
                    color = if (showNegative) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (showNegative) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "负向词",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (showNegative) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (negativePrompt.isNotBlank()) {
                    Text(
                        text = "已设置 ${negativePrompt.length} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            if (showNegative) {
                OutlinedTextField(
                    value = negativePrompt,
                    onValueChange = onNegativeChange,
                    placeholder = {
                        Text(
                            text = "不要出现的内容：lowres, bad hands, watermark…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp, max = 110.dp),
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelSelector(
                    modelId = settings.imageGenerationModelId,
                    providers = settings.providers,
                    type = ModelType.IMAGE,
                    onlyIcon = true,
                    onSelect = { model -> onModelChange(model.id.toString()) }
                )

                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = "添加参考图",
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(onClick = onShowSettings) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = "参数",
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                val vibes = referenceImages.size + jsonVibeCount
                Text(
                    text = if (vibes > 0) "vibe ×$vibes" else "文生图",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (vibes > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.width(8.dp))

                val canSend = prompt.isNotBlank()
                Surface(
                    onClick = {
                        if (isGenerating) onCancel() else if (canSend) onGenerate()
                    },
                    enabled = isGenerating || canSend,
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    color = when {
                        isGenerating -> MaterialTheme.colorScheme.errorContainer
                        !canSend -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> MaterialTheme.colorScheme.primary
                    },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isGenerating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                            contentDescription = stringResource(R.string.imggen_page_generate_image),
                            tint = when {
                                isGenerating -> MaterialTheme.colorScheme.onErrorContainer
                                !canSend -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else -> MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box {
                    AsyncImage(
                        model = File(image),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    Surface(
                        onClick = { onRemove(image) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ----------------------------------- 图库 ----------------------------------- */

@Composable
private fun ImageGalleryScreen(
    vm: ImgGenVM,
) {
    val generatedImages = vm.generatedImages.collectAsLazyPagingItems()
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val pullToRefreshState = rememberPullToRefreshState()
    var detailImage by remember { mutableStateOf<GeneratedImage?>(null) }

    detailImage?.let { image ->
        val meta = remember(image.filePath) {
            runCatching {
                val file = File("${image.filePath}.json")
                if (file.exists()) org.json.JSONObject(file.readText()) else null
            }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { detailImage = null },
            title = { Text("出图信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = image.model,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(image.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (meta != null) {
                        val seed = meta.optLong("seed", -1L)
                        val w = meta.optInt("width", 0)
                        val h = meta.optInt("height", 0)
                        val steps = meta.optInt("steps", 0)
                        val scale = meta.optDouble("scale", 0.0)
                        val sampler = meta.optString("sampler", "")
                        val artist = meta.optString("artist", "")
                        val vibe = meta.optInt("vibe", 0)
                        Text(
                            text = buildString {
                                if (w > 0 && h > 0) append("${w}×${h}  ")
                                if (seed >= 0) append("seed $seed  ")
                                if (steps > 0) append("steps $steps  ")
                                if (scale > 0) append("CFG $scale")
                                if (sampler.isNotBlank()) append("\n$sampler")
                                if (artist.isNotBlank()) append("\n画师串：$artist")
                                if (vibe > 0) append("\nvibe ×$vibe")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Text(
                        text = meta?.optString("full_prompt")?.ifBlank { image.prompt } ?: image.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(image.prompt))
                        toaster.show(message = "Prompt copied to clipboard", type = ToastType.Success)
                        detailImage = null
                    }) { Text("复制") }

                    if (meta != null) {
                        TextButton(onClick = {
                            val seed = meta.optLong("seed", -1L).takeIf { it >= 0 }
                            vm.reuseImageMeta(
                                body = meta.optString("body").ifBlank { image.prompt },
                                seed = seed,
                                width = meta.optInt("width", 832).takeIf { it > 0 } ?: 832,
                                height = meta.optInt("height", 1216).takeIf { it > 0 } ?: 1216,
                            )
                            toaster.show(message = "参数已填入生图页，可直接再画一张", type = ToastType.Success)
                            detailImage = null
                        }) { Text("用这张的参数") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { detailImage = null }) { Text("关闭") }
            },
        )
    }

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { generatedImages.refresh() },
        state = pullToRefreshState
    ) {
        if (generatedImages.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = HugeIcons.Image03,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.imggen_page_no_generated_images),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = generatedImages.itemCount,
                    key = generatedImages.itemKey { it.id },
                    contentType = generatedImages.itemContentType { "GeneratedImage" }
                ) { index ->
                    val image = generatedImages[index]
                    image?.let {
                        var showPreview by remember { mutableStateOf(false) }

                        Card(shape = RoundedCornerShape(20.dp)) {
                            Column {
                                AsyncImage(
                                    model = File(it.filePath),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clickable { showPreview = true },
                                    contentScale = ContentScale.Crop
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = it.model,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = it.prompt.take(24) + if (it.prompt.length > 24) "…" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        modifier = Modifier.clickable { detailImage = it },
                                    )

                                    Row {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(it.prompt))
                                                toaster.show(
                                                    message = "Prompt copied to clipboard",
                                                    type = ToastType.Success
                                                )
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Copy01,
                                                contentDescription = "Copy prompt",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        filesManager.saveMessageImage(context, "file://${it.filePath}")
                                                        toaster.show(
                                                            message = context.getString(R.string.imggen_page_image_saved_success),
                                                            type = ToastType.Success
                                                        )
                                                    } catch (e: Exception) {
                                                        toaster.show(
                                                            message = context.getString(
                                                                R.string.imggen_page_save_failed,
                                                                e.message
                                                            ),
                                                            type = ToastType.Error
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.FloppyDisk,
                                                contentDescription = stringResource(R.string.imggen_page_save),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { vm.deleteImage(it) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Delete01,
                                                contentDescription = stringResource(R.string.imggen_page_delete),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (showPreview) {
                            ImagePreviewDialog(
                                images = listOf(it.filePath),
                                onDismissRequest = { showPreview = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsBottomSheet(
    vm: ImgGenVM,
    settings: Settings,
    numberOfImages: Int,
    aspectRatio: ImageAspectRatio,
    scope: CoroutineScope,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.imggen_page_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_model_selection)) },
                description = { Text(stringResource(R.string.imggen_page_model_selection_desc)) }
            ) {
                ModelSelector(
                    modelId = settings.imageGenerationModelId,
                    providers = settings.providers,
                    type = ModelType.IMAGE,
                    onlyIcon = false,
                    onSelect = { model ->
                        scope.launch {
                            vm.settingsStore.update { oldSettings ->
                                oldSettings.copy(imageGenerationModelId = model.id)
                            }
                        }
                    }
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_generation_count)) },
                description = { Text(stringResource(R.string.imggen_page_generation_count_desc)) }
            ) {
                OutlinedNumberInput(
                    value = numberOfImages,
                    onValueChange = vm::updateNumberOfImages,
                    modifier = Modifier.width(120.dp)
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_aspect_ratio)) },
                description = { Text(stringResource(R.string.imggen_page_aspect_ratio_desc)) }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageAspectRatio.entries.forEach { ratio ->
                        FilterChip(
                            selected = aspectRatio == ratio,
                            onClick = { vm.updateAspectRatio(ratio) },
                            label = { Text(ratio.label()) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/* ----------------------------------- 工具函数 ----------------------------------- */

private fun ImageAspectRatio.ratio(): Float = when (this) {
    ImageAspectRatio.SQUARE -> 1f
    ImageAspectRatio.PORTRAIT -> 3f / 4f
    ImageAspectRatio.LANDSCAPE -> 4f / 3f
}

private fun ImageAspectRatio.shortLabel(): String = when (this) {
    ImageAspectRatio.SQUARE -> "1:1"
    ImageAspectRatio.PORTRAIT -> "3:4"
    ImageAspectRatio.LANDSCAPE -> "4:3"
}

private fun ImageAspectRatio.label(): String = when (this) {
    ImageAspectRatio.SQUARE -> "1:1 方形"
    ImageAspectRatio.PORTRAIT -> "3:4 竖版"
    ImageAspectRatio.LANDSCAPE -> "4:3 横版"
}

/** 画师串：存多套画风组合，选中后拼在提示词最前面 */
@Composable
private fun ArtistSection(
    vm: ImgGenVM,
    artists: List<ArtistStyle>,
    activeArtistId: String?,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    GlassCard {
        Text("画师串", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = "存几套常用画风，选中后拼在提示词最前面；换风格只换这一处，不用逐条改提示词",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = activeArtistId == null,
                onClick = { vm.setActiveArtist(null) },
                label = { Text("不使用") },
                shape = RoundedCornerShape(12.dp),
            )
            artists.forEach { artist ->
                FilterChip(
                    selected = activeArtistId == artist.id,
                    onClick = { vm.setActiveArtist(artist.id) },
                    label = { Text(artist.name) },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称（例如：赛璐璐 / 厚涂）") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("画师串") },
            placeholder = { Text("artist:wlop, artist:ask, very aesthetic") },
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Surface(
            onClick = {
                vm.saveArtist(name, value)
                name = ""
                value = ""
            },
            enabled = value.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                text = "存为画师串",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        artists.forEach { artist ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (artist.id == activeArtistId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = artist.value,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                TextButton(onClick = { vm.deleteArtist(artist.id) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** NAI 专用参数：字段名按 NovelAI 官方 API，中转不认识时会被忽略，不影响其它字段 */
@Composable
private fun NaiTab(
    vm: ImgGenVM,
    nai: NaiParams,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启用 NAI 参数",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "只用 NAI 系模型时打开；走 GPT 或普通中转时保持关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = nai.enabled,
                    onCheckedChange = { on -> vm.updateNaiParams { it.copy(enabled = on) } },
                )
            }
        }

        if (!nai.enabled) {
            Text(
                text = "打开后这些参数会写进请求体（steps / scale / sampler / noise_schedule / seed / ucPreset / cfg_rescale / uc_strength / sm / sm_dyn）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            return@Column
        }

        GlassCard {
            NaiNumberField(
                label = "采样步数 Steps",
                hint = "推荐 28，越高越细但越慢",
                value = nai.steps.toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.let { v -> vm.updateNaiParams { it.copy(steps = v.coerceIn(1, 50)) } }
                },
            )
            NaiNumberField(
                label = "提示词相关性 CFG Scale",
                hint = "推荐 5，越高越贴提示词但越僵硬",
                value = nai.scale.toString(),
                onValueChange = { raw ->
                    raw.toFloatOrNull()?.let { v -> vm.updateNaiParams { it.copy(scale = v.coerceIn(0f, 10f)) } }
                },
            )

            NaiChipRow(
                label = "采样器 Sampler",
                options = listOf(
                    "Euler Ancestral",
                    "Euler",
                    "DPM++ 2S Ancestral",
                    "DPM++ 2M SDE",
                    "DPM++ 2M",
                    "DPM++ SDE",
                    "DDIM",
                ),
                selected = nai.sampler,
                shortLabel = { it.removePrefix("DPM++ ").take(12) },
                onSelect = { v -> vm.updateNaiParams { it.copy(sampler = v) } },
            )

            NaiChipRow(
                label = "噪声调度 Noise Schedule",
                options = listOf("Native", "Karras", "Exponential", "Polyexponential"),
                selected = nai.noiseSchedule,
                shortLabel = { it },
                onSelect = { v -> vm.updateNaiParams { it.copy(noiseSchedule = v) } },
            )

            NaiNumberField(
                label = "随机种子 Seed",
                hint = "-1 随机；固定后同参数可复现",
                value = nai.seed.toString(),
                onValueChange = { raw -> raw.toLongOrNull()?.let { v -> vm.updateNaiParams { it.copy(seed = v) } } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.updateNaiParams { it.copy(seed = -1L) } }) { Text("随机") }
                TextButton(onClick = { vm.updateNaiParams { it.copy(seed = (1..9_999_999).random().toLong()) } }) { Text("掷一个") }
            }

            NaiChipRow(
                label = "负向预设 UC Preset",
                options = listOf(
                    "Preset 0 · Heavy",
                    "Preset 1 · Light",
                    "Preset 2 · Human Focus",
                    "Preset 3 · None",
                ),
                selected = "Preset ${nai.ucPreset.coerceIn(0, 3)} · " + when (nai.ucPreset.coerceIn(0, 3)) {
                    0 -> "Heavy"
                    1 -> "Light"
                    2 -> "Human Focus"
                    else -> "None"
                },
                shortLabel = { it.removePrefix("Preset ").replace(" · ", " ") },
                onSelect = { v ->
                    val index = v.removePrefix("Preset ").substringBefore(" ").toIntOrNull() ?: 0
                    vm.updateNaiParams { it.copy(ucPreset = index.coerceIn(0, 3)) }
                },
            )

            NaiNumberField(
                label = "CFG Rescale",
                hint = "0–1，降低 CFG 过度饱和，推荐 0",
                value = nai.cfgRescale.toString(),
                onValueChange = { raw ->
                    raw.toFloatOrNull()?.let { v -> vm.updateNaiParams { it.copy(cfgRescale = v.coerceIn(0f, 1f)) } }
                },
            )
            NaiNumberField(
                label = "负向强度 Undesired Content",
                hint = "推荐 1",
                value = nai.ucStrength.toString(),
                onValueChange = { raw ->
                    raw.toFloatOrNull()?.let { v -> vm.updateNaiParams { it.copy(ucStrength = v.coerceIn(0f, 2f)) } }
                },
            )
        }

        GlassCard {
            NaiSwitchRow(
                title = "质量词 Quality Tags",
                desc = "在提示词后补 best quality, very aesthetic, absurdres",
                checked = nai.qualityTags,
                onChange = { on -> vm.updateNaiParams { it.copy(qualityTags = on) } },
            )
            NaiSwitchRow(
                title = "SMEA",
                desc = "VAE 平滑，减少细节噪点",
                checked = nai.smea,
                onChange = { on -> vm.updateNaiParams { it.copy(smea = on) } },
            )
            NaiSwitchRow(
                title = "SMEA DYN",
                desc = "动态 SMEA，与 SMEA 一起开效果更稳",
                checked = nai.smeaDyn,
                onChange = { on -> vm.updateNaiParams { it.copy(smeaDyn = on) } },
            )
            NaiSwitchRow(
                title = "Variety+",
                desc = "提升画面多样性（官方字段 skip_cfg_above_sigma=19）",
                checked = nai.varietyBoost,
                onChange = { on -> vm.updateNaiParams { it.copy(varietyBoost = on) } },
            )
            NaiSwitchRow(
                title = "Dynamic Thresholding",
                desc = "动态阈值，高 CFG 时抑制过曝",
                checked = nai.dynamicThresholding,
                onChange = { on -> vm.updateNaiParams { it.copy(dynamicThresholding = on) } },
            )
            NaiSwitchRow(
                title = "Use Coords",
                desc = "启用坐标 tagging（use_coords）",
                checked = nai.useCoords,
                onChange = { on -> vm.updateNaiParams { it.copy(useCoords = on) } },
            )
            NaiSwitchRow(
                title = "归一化 Vibe 强度",
                desc = "多参考图归一化强度（normalize_reference_strength_multiple）",
                checked = nai.normalizeReferenceStrength,
                onChange = { on -> vm.updateNaiParams { it.copy(normalizeReferenceStrength = on) } },
            )
            NaiSwitchRow(
                title = "Prefer Brownian",
                desc = "欧拉祖先采样器 Brownian 噪声轨迹（prefer_brownian）",
                checked = nai.preferBrownian,
                onChange = { on -> vm.updateNaiParams { it.copy(preferBrownian = on) } },
            )
        }

        Text(
            text = "提示：不同中转接受的字段不一样，若某家中转报「未知参数」，先关掉这里，再把需要的字段填进模型的「自定义请求体」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun NaiNumberField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun NaiChipRow(
    label: String,
    options: List<String>,
    selected: String,
    shortLabel: (String) -> String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
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
                    label = { Text(shortLabel(option), style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

@Composable
private fun NaiSwitchRow(
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

/** 顶部渠道条：一眼看到当前用哪套出图配置 */
@Composable
private fun ChannelBar(
    channels: List<ImgChannel>,
    activeChannelId: String?,
    onOpen: () -> Unit,
) {
    val active = channels.firstOrNull { it.id == activeChannelId }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Colors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = active?.name ?: if (channels.isEmpty()) "还没有渠道" else "未选择渠道",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = active?.let { "${it.protocol.lowercase().let { p -> if (p == "novelai") "NAI 原生" else "OpenAI 兼容" }} · ${it.modelId}" }
                        ?: "点这里新建 / 切换出图配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "切换 ▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ChannelSheet(
    channels: List<ImgChannel>,
    activeChannelId: String?,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    onEdit: (ImgChannel) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "出图渠道",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "＋ 新建",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onCreate() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Text(
                text = "每套渠道自带协议 / 站点 / Key / 模型，切换渠道不动聊天提供商",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (channels.isEmpty()) {
                Text(
                    text = "还没有渠道。新建一个，填站点地址和 Key 就能出图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            channels.forEach { channel ->
                val selected = channel.id == activeChannelId
                Surface(
                    onClick = { onSelect(channel.id) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${if (channel.protocol == "OPENAI") "OpenAI 兼容" else "NAI 原生"} · ${channel.modelId.ifBlank { "未填模型" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onEdit(channel) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDelete(channel.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelEditorDialog(
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
        title = { Text(if (initial.baseUrl.isBlank()) "新建渠道" else "编辑渠道") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NOVELAI" to "NAI 原生", "OPENAI" to "OpenAI 兼容").forEach { (value, label) ->
                        FilterChip(
                            selected = protocol == value,
                            onClick = { protocol = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
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
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 ID") },
                    placeholder = { Text("nai-diffusion-4-5-full") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Text(
                    text = if (protocol == "NOVELAI") "NAI 原生会请求 {站点}/ai/generate-image，站点地址不要带 /v1"
                    else "OpenAI 兼容会请求 {站点}/images/generations，站点地址通常以 /v1 结尾",
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
