@file:OptIn(ExperimentalMaterial3Api::class)

package com.beatslide.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.beatslide.app.R
import com.beatslide.app.data.MediaImporter
import com.beatslide.app.data.Project
import com.beatslide.app.data.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaTab(project: Project) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var confirmClear by remember { mutableStateOf(false) }
    val id = project.id

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "正在匯入音樂…"; progress = 0f
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { MediaImporter.importMusic(context, id, uri) } }
            busy = null
            r.onSuccess { info ->
                val old = ProjectStore.get(id)?.musicFile
                ProjectStore.update(id) { it.copy(musicFile = info.fileName, musicName = info.displayName, musicDurationMs = info.durationMs) }
                if (old != null && old != info.fileName) ProjectStore.deleteFiles(id, listOf(old))
            }.onFailure { Toast.makeText(context, it.message ?: "匯入失敗", Toast.LENGTH_LONG).show() }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var failed = 0
            uris.forEachIndexed { i, uri ->
                busy = "正在匯入圖片 ${i + 1} / ${uris.size}"
                progress = i.toFloat() / uris.size
                val name = withContext(Dispatchers.IO) { MediaImporter.importImage(context, id, uri) }
                if (name != null) ProjectStore.update(id) { it.copy(images = it.images + name) } else failed++
            }
            busy = null
            if (failed > 0) Toast.makeText(context, "有 $failed 張圖片無法讀取，已略過", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionTitle("音樂")
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Ico(R.drawable.ic_music, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.musicName ?: "尚未選擇音樂", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (project.musicFile != null) "長度 ${formatTime(project.musicDurationMs.toDouble())}" else "支援 mp3、m4a、wav、ogg、flac 等",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(enabled = busy == null, onClick = { musicPicker.launch(arrayOf("audio/*")) }) {
                    Text(if (project.musicFile == null) "選擇音樂" else "更換")
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("圖片（${project.images.size}）", Modifier.weight(1f))
            IconButton(enabled = project.images.size > 1 && busy == null, onClick = {
                ProjectStore.update(id) { it.copy(images = it.images.shuffled()) }
            }) { Ico(R.drawable.ic_shuffle, "隨機打亂") }
            IconButton(enabled = project.images.isNotEmpty() && busy == null, onClick = { confirmClear = true }) {
                Ico(R.drawable.ic_delete, "全部刪除")
            }
            FilledTonalButton(enabled = busy == null, onClick = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Ico(R.drawable.ic_add, size = 18.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(4.dp)); Text("新增")
            }
        }

        busy?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        if (project.images.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ico(R.drawable.ic_photos, tint = MaterialTheme.colorScheme.outline, size = 48.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("按「新增」從相簿一次選多張圖片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("從相簿選圖片") }
                }
            }
        } else {
            Text("長按圖片拖曳可調整順序", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ReorderableImageGrid(
                project = project,
                onMove = { fromKey, toKey ->
                    ProjectStore.update(id) {
                        val from = it.images.indexOf(fromKey); val to = it.images.indexOf(toKey)
                        if (from < 0 || to < 0 || from == to) it
                        else it.copy(images = it.images.toMutableList().apply { add(to, removeAt(from)) })
                    }
                },
                onDelete = { name ->
                    ProjectStore.update(id) { it.copy(images = it.images - name) }
                    ProjectStore.deleteFiles(id, listOf(name))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("刪除全部圖片？") },
            text = { Text("這個專案裡的 ${project.images.size} 張圖片都會移除（相簿原檔不受影響）。") },
            confirmButton = {
                TextButton(onClick = {
                    val names = project.images
                    ProjectStore.update(id) { it.copy(images = emptyList()) }
                    ProjectStore.deleteFiles(id, names)
                    confirmClear = false
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ReorderableImageGrid(
    project: Project,
    onMove: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }   // 被拖曳項目一開始的位置
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    // 移動後要等 LazyGrid 重新排版，才用新位置判斷下一次交換（避免來回抖動）
    var layoutAtMove by remember { mutableStateOf<Any?>(null) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }

    fun itemAt(p: Offset): LazyGridItemInfo? = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
        p.x >= it.offset.x && p.x <= it.offset.x + it.size.width && p.y >= it.offset.y && p.y <= it.offset.y + it.size.height
    }

    fun checkSwap() {
        val key = draggingKey ?: return
        val layout = gridState.layoutInfo
        if (layout === layoutAtMove) return
        val info = layout.visibleItemsInfo
        val dragged = info.firstOrNull { it.key == key } ?: return
        val center = Offset(
            dragStart.x + dragDelta.x + dragged.size.width / 2f,
            dragStart.y + dragDelta.y + dragged.size.height / 2f,
        )
        val target = itemAt(center)?.takeIf { it.key != key } ?: return
        val first = gridState.firstVisibleItemIndex
        val firstOff = gridState.firstVisibleItemScrollOffset
        layoutAtMove = layout
        currentOnMove(key, target.key as String)
        // 移動第一個可見項目時，LazyGrid 會想跟著它捲動，這裡把捲動位置固定住
        if (dragged.index == first || target.index == first) {
            scope.launch { gridState.scrollToItem(first, firstOff) }
        }
    }

    // 拖到上下邊緣時自動捲動
    LaunchedEffect(draggingKey) {
        if (draggingKey == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val h = gridState.layoutInfo.viewportSize.height
            val speed = when {
                pointerY < edge -> -(edge - pointerY) / edge * 18f
                pointerY > h - edge -> (pointerY - (h - edge)) / edge * 18f
                else -> 0f
            }
            if (speed != 0f) {
                val consumed = gridState.scrollBy(speed)
                // 捲動時手指不動，但被拖曳的東西相對內容在移動
                if (consumed != 0f) checkSwap()
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        state = gridState,
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    itemAt(pos)?.let {
                        draggingKey = it.key as String
                        dragStart = Offset(it.offset.x.toFloat(), it.offset.y.toFloat())
                        dragDelta = Offset.Zero
                        pointerY = pos.y
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragDelta += amount
                    pointerY = change.position.y
                    checkSwap()
                },
                onDragEnd = { draggingKey = null; dragDelta = Offset.Zero },
                onDragCancel = { draggingKey = null; dragDelta = Offset.Zero },
            )
        },
    ) {
        itemsIndexed(project.images, key = { _, name -> name }) { index, name ->
            val dragging = name == draggingKey
            val mod = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer {
                    val cur = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == name }
                    if (cur != null) {
                        translationX = dragStart.x + dragDelta.x - cur.offset.x
                        translationY = dragStart.y + dragDelta.y - cur.offset.y
                    }
                    scaleX = 1.08f; scaleY = 1.08f
                    shadowElevation = 16f
                }
            } else Modifier.animateItem()
            Box(mod.aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                Thumb(ProjectStore.file(project.id, name), Modifier.fillMaxSize())
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (!dragging) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(onClick = { onDelete(name) }, modifier = Modifier.size(28.dp)) {
                            Ico(R.drawable.ic_close, "刪除", tint = Color.White, size = 16.dp)
                        }
                    }
                }
            }
        }
    }
}
