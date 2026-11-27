package com.beatslide.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.beatslide.app.audio.BeatMath
import com.beatslide.app.audio.MusicPlayer
import com.beatslide.app.data.FitMode
import com.beatslide.app.data.ProjectStore
import com.beatslide.app.render.BitmapLoader
import com.beatslide.app.render.SlideView
import com.beatslide.app.ui.BeatSlideTheme
import com.beatslide.app.ui.Ico
import com.beatslide.app.ui.PreviewState
import com.beatslide.app.ui.TransportBar
import kotlinx.coroutines.delay

class PlayerActivity : ComponentActivity() {
    private lateinit var preview: PreviewState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val project = id?.let { ProjectStore.get(it) }
        if (project?.musicFile == null || project.images.isEmpty()) { finish(); return }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val player = MusicPlayer()
        preview = PreviewState(player, BitmapLoader())
        player.onStateChanged = { preview.playing = player.isPlaying }
        player.load(ProjectStore.file(project.id, project.musicFile))
        preview.loaded = player.isLoaded
        player.play()

        setContent { BeatSlideTheme { PlayerScreen(project.id, preview, onBack = { finish() }) } }
    }

    override fun onPause() {
        super.onPause()
        if (::preview.isInitialized) preview.player.pause()
        ProjectStore.flush()
    }

    override fun onDestroy() {
        if (::preview.isInitialized) {
            preview.player.release()
            preview.loader.close()
        }
        super.onDestroy()
    }

    companion object { const val EXTRA_ID = "project_id" }
}

@Composable
private fun PlayerScreen(id: String, preview: PreviewState, onBack: () -> Unit) {
    val projects by ProjectStore.projects.collectAsState()
    val project = projects.firstOrNull { it.id == id } ?: return
    var controls by remember { mutableStateOf(true) }
    var showLamp by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }

    // 播放中 3 秒沒操作就隱藏控制列
    LaunchedEffect(controls, touch, preview.playing, preview.dragTime) {
        if (controls && preview.playing && preview.dragTime == null) { delay(3000); controls = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> SlideView(ctx, preview.loader).apply { timeSource = { preview.player.positionMs() } } },
            update = { v -> v.project = project; v.overrideTime = preview.dragTime; v.showLamp = showLamp },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { controls = !controls; touch++ },
                onDoubleTap = { preview.player.toggle(); controls = true; touch++ },
            )
        })

        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // 上方列
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Ico(R.drawable.ic_back, "返回", tint = Color.White) }
                    Text(project.name, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showLamp = !showLamp; touch++ }) {
                        Ico(R.drawable.ic_lamp, "節拍指示燈", tint = if (showLamp) Color(0xFFFF4F8B) else Color.White)
                    }
                    IconButton(onClick = {
                        ProjectStore.update(id) { it.copy(fitMode = if (it.fitMode == FitMode.FILL) FitMode.FIT else FitMode.FILL) }
                        touch++
                    }) { Ico(R.drawable.ic_fit, "切換顯示方式", tint = Color.White) }
                }
                // 下方列
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    val info by produceState(BeatMath.compute(project, 0.0), project) {
                        while (true) { value = BeatMath.compute(project, preview.time()); delay(100) }
                    }
                    val n = project.images.size
                    Row {
                        Text(
                            if (info.beforeStart) "等待第一拍…" else
                                "第 ${info.beatIndex + 1} 拍 · 第 ${BeatMath.imageIndex(info.slideNum, n) + 1} / $n 張",
                            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${"%.1f".format(project.bpm)} BPM · 每 ${project.beatsPerSlide} 拍換圖 · " +
                                if (project.fitMode == FitMode.FILL) "填滿" else "完整顯示",
                            color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TransportBar(preview, light = true)
                }
            }
        }
    }
}
