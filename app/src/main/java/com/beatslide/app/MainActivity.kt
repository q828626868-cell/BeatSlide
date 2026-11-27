package com.beatslide.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.beatslide.app.data.ProjectStore
import com.beatslide.app.ui.BeatSlideTheme
import com.beatslide.app.ui.EditorScreen
import com.beatslide.app.ui.ProjectListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            BeatSlideTheme {
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                val id = openId
                if (id == null || ProjectStore.get(id) == null) {
                    ProjectListScreen(onOpen = { openId = it }, onPlay = ::startPlayer)
                } else {
                    BackHandler { openId = null }
                    EditorScreen(projectId = id, onBack = { openId = null }, onPlay = { startPlayer(id) })
                }
            }
        }
    }

    private fun startPlayer(id: String) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_ID, id))
    }

    override fun onStop() {
        super.onStop()
        ProjectStore.flush()
    }
}
