package com.beatslide.app

import android.app.Application
import com.beatslide.app.data.ProjectStore

class BeatSlideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProjectStore.init(this)
    }
}
