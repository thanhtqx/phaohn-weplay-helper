package com.phaohn.spyhelper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PhaoHNApp : Application() {
    lateinit var repository: WordRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = WordDatabase.get(this)
        repository = WordRepository(db.wordDao(), db.historyDao())
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OverlayService.CHANNEL_ID,
                getString(R.string.overlay_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        fun repo(app: Application): WordRepository = (app as PhaoHNApp).repository
    }
}