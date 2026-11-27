@file:OptIn(ExperimentalMaterial3Api::class)

package com.beatslide.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beatslide.app.R
import com.beatslide.app.audio.BeatMath
import com.beatslide.app.audio.MusicPlayer
import com.beatslide.app.data.Project
import com.beatslide.app.data.ProjectStore
import com.beatslide.app.render.BitmapLoader
import com.beatslide.app.render.SlideView
import kotlinx.coroutines.delay
import kotlin.math.exp

/** 編輯頁共用的播放狀態 */
class PreviewState(val player: MusicPlayer, val loader: BitmapLoader) {
    var playing by mutableStateOf(false)
    /** 音樂是否已載入（Compose 可觀察） */
    var loaded by mutableStateOf(false)
    /** 拖動進度條時的預覽時間 */
    var dragTime by mutableStateOf<Double?>(null)
    fun time(): Double = dragTime ?: player.positionMs()
}

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit, onPlay: () -> Unit) {
    val projects by ProjectStore.projects.collectAsState()
    val project = projects.firstOrNull { it.id == projectId } ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    val preview = remember { PreviewState(MusicPlayer(), BitmapLoader()) }
    LaunchedEffect(project.musicFile) {
        preview.player.load(project.musicFile?.let { ProjectStore.file(project.id, it) })
        preview.playing = preview.player.isPlaying
        preview.loaded = preview.player.isLoaded
    }
    DisposableEffect(Unit) {
        preview.player.onStateChanged = { preview.playing = preview.player.isPlaying }
        onDispose { preview.player.release(); preview.loader.close() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) preview.player.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val canPlay = project.musicFile != null && project.images.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Ico(R.drawable.ic_back, "返回") } },
                title = {
                    Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true })
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Ico(R.drawable.ic_edit, "重新命名") }
                    IconButton(enabled = canPlay, onClick = { exporting = true }) {
                        Ico(R.drawable.ic_movie, "匯出影片",
                            tint = if (canPlay) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    }
                    FilledIconButton(
                        enabled = canPlay,
                        onClick = { preview.player.pause(); onPlay() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Ico(R.drawable.ic_fullscreen, "全螢幕播放", tint = MaterialTheme.colorScheme.onPrimary) }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("素材", "節拍", "特效").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> MediaTab(project)
                1 -> BeatTab(project, preview)
                else -> EffectsTab(project, preview)
            }
        }
    }

    if (exporting) {
        ExportDialog(project, preview, onDismiss = { exporting = false })
    }
    if (renaming) {
        NameDialog("重新命名", project.name, "儲存", onDismiss = { renaming = false }) {
            ProjectStore.rename(project.id, it); renaming = false
        }
    }
}

/** 小預覽畫面 + 播放控制列（節拍頁、特效頁共用） */
@Composable
fun PreviewPanel(project: Project, preview: PreviewState) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .clickable(enabled = preview.loaded) { preview.player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (project.images.isEmpty()) {
                Text("先到「素材」頁加入圖片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AndroidView(
                    factory = { ctx -> SlideView(ctx, preview.loader).apply { timeSource = { preview.player.positionMs() } } },
                    update = { v -> v.project = project; v.overrideTime = preview.dragTime },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        TransportBar(preview)
    }
}

@Composable
fun TransportBar(preview: PreviewState, light: Boolean = false) {
    val player = preview.player
    val pos by produceState(0.0, player) {
        while (true) { value = player.positionMs(); delay(50) }
    }
    val dur = player.durationMs.toDouble().coerceAtLeast(1.0)
    val shown = preview.dragTime ?: pos
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = { player.toggle() }, enabled = preview.loaded,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (light) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primaryContainer),
        ) {
            Ico(if (preview.playing) R.drawable.ic_pause else R.drawable.ic_play, if (preview.playing) "暫停" else "播放",
                tint = if (light) Color.White else MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(8.dp))
        Slider(
            value = (shown / dur).toFloat().coerceIn(0f, 1f),
            onValueChange = { preview.dragTime = it * dur },
            onValueChangeFinished = {
                preview.dragTime?.let { player.seekTo(it) }
                preview.dragTime = null
            },
            enabled = preview.loaded,
            modifier = Modifier.weight(1f),
            colors = if (light) SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)) else SliderDefaults.colors(),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${formatTime(shown)} / ${formatTime(dur)}",
            style = MaterialTheme.typography.labelMedium,
            color = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 每一格畫面更新一次的音樂時間（給節拍指示燈用） */
@Composable
fun rememberFrameTime(preview: PreviewState): Double {
    var t by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(preview) {
        while (true) withFrameMillis { t = preview.time() }
    }
    return t
}

/** 節拍指示燈：一小節幾拍就幾顆，目前這拍亮起，重拍是粉紅色 */
@Composable
fun BeatLampRow(project: Project, preview: PreviewState) {
    val t = rememberFrameTime(preview)
    val info = BeatMath.compute(project, t)
    val bpb = project.beatsPerBar.coerceIn(1, 16)
    val outline = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(28.dp)) {
            val gap = 6.dp.toPx()
            val r = ((size.width - gap * (bpb - 1)) / bpb / 2f).coerceAtMost(11.dp.toPx())
            val total = bpb * r * 2 + gap * (bpb - 1)
            var x = (size.width - total) / 2f + r
            for (i in 0 until bpb) {
                val c = Offset(x, size.height / 2f)
                val color = if (i == 0) AccentPink else Accent
                if (!info.beforeStart && i == info.beatInBar) {
                    val a = exp(-info.sinceBeatMs / 220.0).toFloat()
                    drawCircle(color.copy(alpha = 0.25f + 0.75f * a), r, c)
                }
                drawCircle(if (i == 0) AccentPink.copy(alpha = 0.6f) else outline, r, c, style = Stroke(1.5.dp.toPx()))
                x += r * 2 + gap
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (info.beforeStart) "第一拍前" else "第 ${info.beatIndex + 1} 拍",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun StepButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}
