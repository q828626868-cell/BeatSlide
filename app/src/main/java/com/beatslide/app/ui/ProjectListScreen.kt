@file:OptIn(ExperimentalMaterial3Api::class)

package com.beatslide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beatslide.app.R
import com.beatslide.app.data.Project
import com.beatslide.app.data.ProjectStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectListScreen(onOpen: (String) -> Unit, onPlay: (String) -> Unit) {
    val projects by ProjectStore.projects.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("BeatSlide") },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Ico(R.drawable.ic_add, tint = MaterialTheme.colorScheme.onPrimary) },
                text = { Text("新增專案") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        val sorted = projects.sortedByDescending { it.updatedAt }
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ico(R.drawable.ic_photos, tint = MaterialTheme.colorScheme.outline, size = 56.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("還沒有專案", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "按右下角「新增專案」，選一首歌和幾張照片，\n讓圖片跟著拍子切換。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sorted, key = { it.id }) { p ->
                    ProjectCard(p, onOpen = { onOpen(p.id) }, onPlay = { onPlay(p.id) },
                        onRename = { renaming = p }, onDelete = { deleting = p })
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "新增專案",
            initial = "專案 ${projects.size + 1}",
            confirm = "建立",
            onDismiss = { showCreate = false },
            onConfirm = { name -> showCreate = false; onOpen(ProjectStore.create(name).id) },
        )
    }
    renaming?.let { p ->
        NameDialog("重新命名", p.name, "儲存", onDismiss = { renaming = null }) { name ->
            ProjectStore.rename(p.id, name); renaming = null
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除專案？") },
            text = { Text("「${p.name}」以及裡面匯入的音樂和圖片都會被刪除（手機相簿裡的原檔不受影響）。") },
            confirmButton = {
                TextButton(onClick = { ProjectStore.delete(p.id); deleting = null }) {
                    Text("刪除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectCard(p: Project, onOpen: () -> Unit, onPlay: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val first = p.images.firstOrNull()
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) {
                if (first != null) Thumb(ProjectStore.file(p.id, first), Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Ico(R.drawable.ic_photos, tint = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${p.images.size} 張圖片 · ${formatBpm(p.bpm)} BPM",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    (p.musicName ?: "尚未選擇音樂") + " · " + dateFmt.format(Date(p.updatedAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.musicFile != null && p.images.isNotEmpty()) {
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) { Ico(R.drawable.ic_play, "播放", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_more, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重新命名") }, leadingIcon = { Ico(R.drawable.ic_edit) },
                        onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("刪除") }, leadingIcon = { Ico(R.drawable.ic_delete) },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("M/d HH:mm", Locale.TAIWAN)

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                label = { Text("專案名稱") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
