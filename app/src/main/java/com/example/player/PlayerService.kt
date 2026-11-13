package com.example.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.UnMusicApplication

/**
 * Menampilkan kontrol media sistem (notifikasi, panel cepat, dan layar kunci) untuk pemutar bersama.
 * Pemutar sebenarnya ada di [MusicPlayerManager]; layanan ini hanya membungkusnya dalam MediaSession.
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        const val CHANNEL_ID = "unmusic_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        val manager = (application as UnMusicApplication).playerManager

        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_playback)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        provider.setSmallIcon(R.drawable.ic_notif_music)
        setMediaNotificationProvider(provider)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, QueuePlayer(manager.exoPlayer, manager))
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        // Pemutar dimiliki MusicPlayerManager, jadi hanya sesi yang dilepas di sini.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /** Next/previous dari notifikasi, layar kunci, dan headset diarahkan ke antrean aplikasi. */
    private class QueuePlayer(
        player: Player,
        private val manager: MusicPlayerManager
    ) : ForwardingPlayer(player) {

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return getAvailableCommands().contains(command)
        }

        override fun hasNextMediaItem(): Boolean = true

        override fun hasPreviousMediaItem(): Boolean = true

        override fun seekToNext() {
            manager.skipToNext()
        }

        override fun seekToNextMediaItem() {
            manager.skipToNext()
        }

        override fun seekToPrevious() {
            manager.skipToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            manager.skipToPrevious()
        }
    }
}
