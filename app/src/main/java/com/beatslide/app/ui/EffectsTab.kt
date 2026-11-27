package com.beatslide.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beatslide.app.data.Effect
import com.beatslide.app.data.EffectConfig
import com.beatslide.app.data.FitMode
import com.beatslide.app.data.Project
import com.beatslide.app.data.ProjectStore
import kotlin.math.roundToInt

@Composable
fun EffectsTab(project: Project, preview: PreviewState) {
    val id = project.id
    fun setEffect(e: Effect, c: EffectConfig) = ProjectStore.update(id) { it.copy(effects = it.effects + (e to c)) }

    Column(Modifier.fillMaxSize()) {
        PreviewPanel(project, preview)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("圖片顯示方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = project.fitMode == FitMode.FILL,
                    onClick = { ProjectStore.update(id) { it.copy(fitMode = FitMode.FILL) } },
                    label = { Text("填滿裁切") })
                FilterChip(selected = project.fitMode == FitMode.FIT,
                    onClick = { ProjectStore.update(id) { it.copy(fitMode = FitMode.FIT) } },
                    label = { Text("完整顯示（加黑邊）") })
            }

            SectionTitle("特效模式")
            ToggleCard(
                title = "隨機模式",
                desc = "每次換圖從已啟用的換圖特效中隨機挑一種（緩慢推近不受影響）",
                checked = project.randomMode,
                onChange = { v -> ProjectStore.update(id) { it.copy(randomMode = v) } },
            )
            Spacer(Modifier.height(8.dp))
            ToggleCard(
                title = "強弱拍模式",
                desc = "落在小節第一拍的換圖用全強度特效，其他拍用較輕的特效",
                checked = project.accentMode,
                onChange = { v -> ProjectStore.update(id) { it.copy(accentMode = v) } },
            ) {
                if (project.accentMode) {
                    Text("弱拍特效強度：${(project.weakBeatStrength * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = project.weakBeatStrength, valueRange = 0f..1f,
                        onValueChange = { v -> ProjectStore.update(id) { it.copy(weakBeatStrength = v) } },
                    )
                }
            }

            SectionTitle("換圖特效")
            Effect.entries.forEach { e ->
                val c = project.effect(e)
                ToggleCard(
                    title = e.label, desc = e.desc, checked = c.enabled,
                    onChange = { setEffect(e, c.copy(enabled = it)) },
                ) {
                    if (c.enabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("強度", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Slider(
                                value = c.intensity, valueRange = 0.1f..1f,
                                onValueChange = { setEffect(e, c.copy(intensity = it)) },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("${(c.intensity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(40.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToggleCard(
    title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = onChange)
            }
            extra()
        }
    }
}
