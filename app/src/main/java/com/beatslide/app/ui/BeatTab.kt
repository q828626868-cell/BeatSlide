@file:OptIn(ExperimentalMaterial3Api::class)

package com.beatslide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatslide.app.audio.BeatMath
import com.beatslide.app.audio.BpmDetector
import com.beatslide.app.data.Project
import com.beatslide.app.data.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private fun clampBpm(v: Double) = (Math.round(v.coerceIn(Project.MIN_BPM, Project.MAX_BPM) * 10.0) / 10.0)

@Composable
fun BeatTab(project: Project, preview: PreviewState) {
    val id = project.id
    fun setBpm(v: Double) = ProjectStore.update(id) { it.copy(bpm = clampBpm(v)) }
    fun setOffset(v: Long) = ProjectStore.update(id) { it.copy(offsetMs = v.coerceIn(0L, 3_600_000L)) }

    Column(Modifier.fillMaxSize()) {
        PreviewPanel(project, preview)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(6.dp))
            BeatLampRow(project, preview)
            if (project.musicFile == null) {
                Text("提示：先到「素材」頁選擇音樂，才能播放對拍。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp))
            }

            // ---------- BPM ----------
            SectionTitle("BPM（每分鐘幾拍）")
            BeatCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(
                        value = formatBpm(project.bpm),
                        label = "BPM",
                        decimal = true,
                        onCommit = { s -> s.toDoubleOrNull()?.let { setBpm(it) } },
                        modifier = Modifier.width(130.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("一拍 = ${"%.1f".format(BeatMath.beatLenMs(project.bpm))} ms",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepButton("−1", Modifier.weight(1f)) { setBpm(project.bpm - 1) }
                    StepButton("−0.1", Modifier.weight(1f)) { setBpm(project.bpm - 0.1) }
                    StepButton("+0.1", Modifier.weight(1f)) { setBpm(project.bpm + 0.1) }
                    StepButton("+1", Modifier.weight(1f)) { setBpm(project.bpm + 1) }
                    StepButton("÷2", Modifier.weight(1f)) { setBpm(project.bpm / 2) }
                    StepButton("×2", Modifier.weight(1f)) { setBpm(project.bpm * 2) }
                }
                Spacer(Modifier.height(12.dp))
                TapTempo(onBpm = { setBpm(it) })
                Spacer(Modifier.height(10.dp))
                AutoDetect(project, onApply = { bpm, offset ->
                    ProjectStore.update(id) { it.copy(bpm = clampBpm(bpm), offsetMs = offset ?: it.offsetMs) }
                })
            }

            // ---------- Offset ----------
            SectionTitle("起點偏移（Offset）")
            BeatCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(
                        value = project.offsetMs.toString(),
                        label = "毫秒",
                        decimal = false,
                        onCommit = { s -> s.toLongOrNull()?.let { setOffset(it) } },
                        modifier = Modifier.width(130.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("第一拍在 ${formatOffset(project.offsetMs)}", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in longArrayOf(-100, -10, -1, 1, 10, 100)) {
                        StepButton(if (d > 0) "+$d" else "$d", Modifier.weight(1f)) { setOffset(project.offsetMs + d) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { setOffset(preview.player.positionMs().roundToLong()) },
                    enabled = preview.loaded,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("播放中按一下：設為第一拍", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val len = BeatMath.beatLenMs(project.bpm)
                        setOffset((preview.player.positionMs() % len).roundToLong())
                    },
                    enabled = preview.loaded,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("對齊這一拍（只調相位，圖片仍從歌曲開頭附近開始）") }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { setOffset(0) }) { Text("Offset 歸零") }
            }

            // ---------- 換圖節奏 ----------
            SectionTitle("換圖節奏")
            BeatCard {
                Text("每幾拍換一張圖", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in intArrayOf(1, 2, 4, 8)) {
                        FilterChip(
                            selected = project.beatsPerSlide == n,
                            onClick = { ProjectStore.update(id) { it.copy(beatsPerSlide = n) } },
                            label = { Text("$n 拍") },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每小節幾拍", style = MaterialTheme.typography.bodyMedium)
                        Text("用來判斷哪一拍是重拍（粉紅色燈）", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StepButton("−", Modifier.width(44.dp)) {
                        ProjectStore.update(id) { it.copy(beatsPerBar = (it.beatsPerBar - 1).coerceAtLeast(1)) }
                    }
                    Text("${project.beatsPerBar}", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 14.dp))
                    StepButton("+", Modifier.width(44.dp)) {
                        ProjectStore.update(id) { it.copy(beatsPerBar = (it.beatsPerBar + 1).coerceAtMost(16)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val slideSec = BeatMath.beatLenMs(project.bpm) * project.beatsPerSlide / 1000.0
                Text("每張圖停留 ${"%.2f".format(slideSec)} 秒", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun BeatCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

/** 數字輸入框：按完成或離開輸入框時才套用，避免打字打到一半被改掉 */
@Composable
private fun NumberField(value: String, label: String, decimal: Boolean, onCommit: (String) -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value) { if (!focused) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { s -> text = s.filter { it.isDigit() || (decimal && it == '.') }.take(8) },
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text); focus.clearFocus() }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) { onCommit(text); text = value }
            focused = it.isFocused
        },
    )
}

/** Tap Tempo：跟著節奏點，用最近 8 次點擊的平均間隔算 BPM；停 2 秒重新開始 */
@Composable
private fun TapTempo(onBpm: (Double) -> Unit) {
    val taps = remember { ArrayList<Long>() }
    var count by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<Double?>(null) }
    var flash by remember { mutableIntStateOf(0) }
    val bg = if (flash % 2 == 1) Accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.primaryContainer
    Box(
        Modifier.fillMaxWidth().height(84.dp)
            .background(bg, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                // 用「按下」的瞬間計時（比放開更準）
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.any { it.pressed && !it.previousPressed }) {
                            val now = System.nanoTime()
                            if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
                            taps.add(now)
                            while (taps.size > 9) taps.removeAt(0)
                            count = taps.size
                            if (taps.size >= 2) {
                                val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
                                current = 60e9 / avg
                                if (taps.size >= 4) onBpm(current!!)
                            }
                            flash++
                        } else if (e.changes.any { !it.pressed && it.previousPressed }) {
                            flash++
                        }
                        e.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TAP", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                when {
                    count == 0 -> "播放音樂，跟著節奏點這裡（點 4 下以上自動套用）"
                    count < 4 -> "已點 $count 下 · 約 ${current?.let { formatBpm(it) } ?: "--"} BPM，繼續點…"
                    else -> "已點 $count 下 · ${formatBpm(current ?: 0.0)} BPM（已套用）"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AutoDetect(project: Project, onApply: (Double, Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<BpmDetector.Result?>(null) }
    var failed by remember { mutableStateOf(false) }

    FilledTonalButton(
        enabled = project.musicFile != null && !running,
        onClick = {
            running = true; progress = 0f; failed = false
            val f = ProjectStore.file(project.id, project.musicFile!!)
            scope.launch {
                val r = withContext(Dispatchers.Default) { BpmDetector.detect(f) { progress = it } }
                running = false
                if (r == null) failed = true else result = r
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) { Text(if (running) "分析中…" else "自動偵測 BPM（建議值）") }
    if (running) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    if (failed) {
        Text("無法分析這首歌，請用 Tap Tempo 或手動輸入。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
    }

    result?.let { r ->
        val conf = when {
            r.confidence > 0.6f -> "高"
            r.confidence > 0.3f -> "中"
            else -> "低"
        }
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("偵測結果") },
            text = {
                Column {
                    Text("${formatBpm(r.bpm)} BPM", style = MaterialTheme.typography.headlineMedium, color = Accent)
                    Spacer(Modifier.height(4.dp))
                    Text("第一拍約在 ${formatOffset(r.offsetMs)} · 可信度：$conf")
                    Spacer(Modifier.height(8.dp))
                    Text("自動偵測只是參考。如果感覺快了或慢了一倍，可以用 ÷2 / ×2 修正，再用 Offset 微調。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { onApply(r.bpm, r.offsetMs); result = null }) { Text("套用 BPM + Offset") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { result = null }) { Text("取消") }
                    TextButton(onClick = { onApply(r.bpm, null); result = null }) { Text("只套用 BPM") }
                }
            },
        )
    }
}
