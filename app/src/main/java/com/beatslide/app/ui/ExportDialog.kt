@file:OptIn(ExperimentalMaterial3Api::class)

package com.beatslide.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.beatslide.app.data.Project
import com.beatslide.app.export.ExportManager
import com.beatslide.app.export.ExportSettings
import com.beatslide.app.export.VideoExporter
import com.beatslide.app.render.SlideView
import java.util.Locale

/** 上次的匯出設定（同一次開 App 內記住） */
private object LastExport {
    var vertical = true
    var fps = 30
    val ranges = HashMap<String, ClosedFloatingPointRange<Float>>()
}

private fun formatTenth(ms: Float): String {
    val t = (ms / 100).toLong().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d.%d", t / 600, (t / 10) % 60, t % 10)
}

@Composable
fun ExportDialog(project: Project, preview: PreviewState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val st by ExportManager.state.collectAsState()
    val mine = st?.takeIf { it.projectId == project.id }
    val running = mine?.running == true
    val otherRunning = st?.running == true && st?.projectId != project.id

    val dur = project.musicDurationMs.toFloat().coerceAtLeast(1000f)
    var vertical by remember { mutableStateOf(LastExport.vertical) }
    var fps by remember { mutableIntStateOf(LastExport.fps) }
    var range by remember {
        mutableStateOf(LastExport.ranges[project.id]?.takeIf { it.endInclusive <= dur } ?: 0f..dur)
    }

    // 一改就記住（關掉對話框再打開也還在）
    LaunchedEffect(vertical, fps, range) {
        LastExport.vertical = vertical; LastExport.fps = fps; LastExport.ranges[project.id] = range
    }

    fun doStart() {
        preview.player.pause()
        ExportManager.start(context, project, ExportSettings(vertical, fps, range.start.toLong(), range.endInclusive.toLong()))
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doStart() else Toast.makeText(context, "需要儲存權限才能把影片存到相簿", Toast.LENGTH_LONG).show()
    }

    // 匯出中讓螢幕保持亮著
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Dialog(
        onDismissRequest = { if (!running) { ExportManager.clear(); onDismiss() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 760.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("匯出影片", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                when {
                    running -> Running(mine!!)
                    mine?.resultUri != null -> Done(mine, onClose = { ExportManager.clear(); onDismiss() })
                    else -> {
                        // ----- 預覽（依匯出比例）
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val m = if (vertical) Modifier.height(240.dp).aspectRatio(9f / 16f)
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            Box(m.clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                                AndroidView(
                                    factory = { ctx ->
                                        SlideView(ctx, preview.loader).apply { timeSource = { preview.player.positionMs() }; showLamp = false }
                                    },
                                    update = { v -> v.project = project; v.overrideTime = preview.dragTime },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TransportBar(preview)

                        SectionTitle("畫面比例")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = vertical, onClick = { vertical = true }, label = { Text("9:16 直式") })
                            FilterChip(selected = !vertical, onClick = { vertical = false }, label = { Text("16:9 橫式") })
                        }
                        val size = remember(vertical, fps) { VideoExporter.outputSize(vertical, fps) }
                        val full = maxOf(size.first, size.second) >= 1920
                        Text("${size.first} × ${size.second}，" + if (vertical) "適合 Reels / Shorts / 抖音" else "適合 YouTube",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!full) {
                            Text("這支手機的編碼器不支援 1080p ${fps}fps，會以 720p 匯出" + if (fps == 60) "（改 30 fps 可輸出 1080p）" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }

                        SectionTitle("每秒格數")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = fps == 30, onClick = { fps = 30 }, label = { Text("30 fps") })
                            FilterChip(selected = fps == 60, onClick = { fps = 60 }, label = { Text("60 fps") })
                        }
                        Text(if (fps == 30) "檔案較小、匯出較快" else "快速特效更滑順，檔案約大 1.6 倍、匯出較慢",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        SectionTitle("匯出範圍")
                        val len = range.endInclusive - range.start
                        Text("${formatTenth(range.start)}  →  ${formatTenth(range.endInclusive)}　（長度 ${formatTenth(len)}）",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        RangeSlider(
                            value = range,
                            onValueChange = { r ->
                                range = if (r.endInclusive - r.start >= 1000f) r
                                else if (r.start != range.start) (r.endInclusive - 1000f).coerceAtLeast(0f)..r.endInclusive
                                else r.start..(r.start + 1000f).coerceAtMost(dur)
                            },
                            valueRange = 0f..dur,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StepButton("起點＝目前位置", Modifier.weight(1f)) {
                                val t = preview.time().toFloat().coerceIn(0f, range.endInclusive - 1000f)
                                range = t..range.endInclusive
                            }
                            StepButton("終點＝目前位置", Modifier.weight(1f)) {
                                val t = preview.time().toFloat().coerceIn(range.start + 1000f, dur)
                                range = range.start..t
                            }
                            StepButton("整首", Modifier.width(64.dp)) { range = 0f..dur }
                        }
                        Text("小技巧：在上面播放到想要的位置，再按「起點／終點＝目前位置」。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))

                        val mbps = (if (fps >= 60) 16.0 else 10.0) + 0.192
                        val estMb = mbps * (len / 1000.0) / 8.0
                        Spacer(Modifier.height(14.dp))
                        Text("預估檔案大小約 ${"%.0f".format(estMb)} MB", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        mine?.error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text("匯出失敗：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (otherRunning) {
                            Spacer(Modifier.height(8.dp))
                            Text("另一個專案正在匯出中，請等它完成。", color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { ExportManager.clear(); onDismiss() }) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = !otherRunning && project.musicFile != null && project.images.isNotEmpty(),
                                onClick = {
                                    val needPerm = Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                                    if (needPerm) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else doStart()
                                },
                            ) { Text("開始匯出") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Running(s: ExportManager.State) {
    Text(if (s.progress < 0.1f) "正在準備音訊…" else "正在產生影片…", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
    Spacer(Modifier.height(8.dp))
    Row {
        Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        s.etaSec?.let {
            Text("剩餘約 ${it / 60}:${"%02d".format(it % 60)}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("匯出時請停在這個畫面，螢幕會保持亮著。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { ExportManager.cancel() }) { Text("取消匯出") }
    }
}

@Composable
private fun Done(s: ExportManager.State, onClose: () -> Unit) {
    val context = LocalContext.current
    val uri = s.resultUri!!
    Text("✅ 匯出完成", style = MaterialTheme.typography.titleMedium, color = Accent)
    Spacer(Modifier.height(6.dp))
    Text("已存到相簿：影片 / BeatSlide", style = MaterialTheme.typography.bodyMedium)
    Text(s.resultName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    s.resultInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onClose) { Text("完成") }
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { Toast.makeText(context, "找不到可以播放影片的 App", Toast.LENGTH_SHORT).show() }
        }) { Text("播放") }
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "分享影片"))
        }) { Text("分享") }
    }
}
