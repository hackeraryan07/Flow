package io.github.aedev.flow.ui.screens.player.stage

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PlayerHardwareController
import io.github.aedev.flow.ui.components.videoplayer.controls.PlayerControlActions
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet

/**
 * Bridge between the Activity's raw key stream and the on-screen player.
 *
 * MainActivity forwards every [KeyEvent] to [dispatch]. While the expanded player is mounted it
 * registers a [handler]; when no player is on screen the handler is null and every key falls
 * through untouched.
 */
internal object PlayerKeyboardShortcuts {
    var handler: ((KeyEvent) -> Boolean)? = null

    // Key codes we consumed on ACTION_DOWN, so the matching ACTION_UP is swallowed too
    // (otherwise Space would "click" whatever button happens to hold focus on key-up).
    private val consumedKeys = HashSet<Int>()

    fun dispatch(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return consumedKeys.remove(event.keyCode)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val h = handler ?: return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val handled = h(event)
        if (handled) consumedKeys.add(event.keyCode)
        return handled
    }
}

private val PlaybackSpeeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private const val ARROW_SEEK_MS = 5_000L
private const val JL_SEEK_MS = 10_000L
private const val SEEK_ACCUMULATE_WINDOW_MS = 800L

private class KeyboardSeekState {
    var pendingTargetMs: Long? = null
    var accumulatedMs: Long = 0L
    var lastSeekAt: Long = 0L
    var volumeBeforeMute: Int = -1
    var toast: Toast? = null
}

/**
 * YouTube-style keyboard shortcuts for the expanded player.
 *
 *  Space / K ........ play / pause
 *  Left / Right ..... seek -5s / +5s          J / L ........ seek -10s / +10s
 *  Up / Down ........ volume up / down        M ............ mute / unmute
 *  F ................ fullscreen              Esc .......... leave fullscreen
 *  C ................ captions on / off
 *  0-9 .............. jump to 0%-90%          Home / End ... start / end
 *  , / . ............ previous / next frame (while paused)
 *  Shift+, / Shift+. slower / faster          (the < and > keys)
 *  Shift+N / Shift+P next / previous video
 *
 * Shortcuts are ignored while any sheet (comments, settings, ...) is open so typing in a text
 * field never triggers them, and while the touch lock is on.
 */
@Composable
internal fun PlayerKeyboardShortcutsEffect(
    session: VideoPlayerStageSession,
    actions: PlayerControlActions,
) {
    val currentSession by rememberUpdatedState(session)
    val currentActions by rememberUpdatedState(actions)
    val seekState = remember { KeyboardSeekState() }

    DisposableEffect(Unit) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val s = currentSession
            val a = currentActions
            val screenState = s.screenState
            val keyCode = event.keyCode

            // Esc closes fullscreen, but only when nothing else is open on top of it.
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (screenState.activeSheet == PlayerSheet.None && s.isFullscreenShown) {
                    a.onFullscreenClick()
                    return@handler true
                }
                return@handler false
            }

            if (screenState.activeSheet != PlayerSheet.None || screenState.isTouchLocked) {
                return@handler false
            }

            val manager = EnhancedPlayerManager.getInstance()
            val isRepeat = event.repeatCount > 0
            val shift = event.isShiftPressed

            fun seekBy(deltaMs: Long) {
                val duration = screenState.duration
                val now = SystemClock.uptimeMillis()
                val continuing = now - seekState.lastSeekAt < SEEK_ACCUMULATE_WINDOW_MS
                val base = seekState.pendingTargetMs?.takeIf { continuing } ?: screenState.currentPosition
                val upper = if (duration > 0) duration else Long.MAX_VALUE
                val target = (base + deltaMs).coerceIn(0L, upper)
                seekState.accumulatedMs =
                    if (continuing && (seekState.accumulatedMs > 0) == (deltaMs > 0)) {
                        seekState.accumulatedMs + deltaMs
                    } else {
                        deltaMs
                    }
                seekState.lastSeekAt = now
                seekState.pendingTargetMs = target
                val seconds = (seekState.accumulatedMs / 1000L).toInt()
                screenState.seekAccumulation = seconds
                if (deltaMs > 0) {
                    screenState.showSeekBackAnimation = false
                    screenState.showSeekForwardAnimation = true
                } else {
                    screenState.showSeekForwardAnimation = false
                    screenState.showSeekBackAnimation = true
                }
                a.onSeek(target)
            }

            fun seekToFraction(fraction: Float) {
                val duration = screenState.duration
                if (duration <= 0L) return
                val target = (duration * fraction).toLong().coerceIn(0L, duration)
                seekState.pendingTargetMs = target
                seekState.lastSeekAt = SystemClock.uptimeMillis()
                seekState.accumulatedMs = 0L
                a.onSeek(target)
            }

            fun adjustVolume(direction: Int) {
                val audioManager = s.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                val overlay = PlayerHardwareController.inAppVolumeOverlayEnabled.value
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    if (overlay) 0 else AudioManager.FLAG_SHOW_UI,
                )
                if (overlay) PlayerHardwareController.notifyVolumeKey()
            }

            fun toggleMute() {
                val audioManager = s.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                val overlay = PlayerHardwareController.inAppVolumeOverlayEnabled.value
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val target =
                    if (current > 0) {
                        seekState.volumeBeforeMute = current
                        0
                    } else {
                        seekState.volumeBeforeMute.takeIf { it > 0 }
                            ?: (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2).coerceAtLeast(1)
                    }
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    target,
                    if (overlay) 0 else AudioManager.FLAG_SHOW_UI,
                )
                if (overlay) PlayerHardwareController.notifyVolumeKey()
            }

            fun changeSpeed(faster: Boolean) {
                val current = s.playerState.playbackSpeed
                val next =
                    if (faster) {
                        PlaybackSpeeds.firstOrNull { it > current + 0.001f } ?: PlaybackSpeeds.last()
                    } else {
                        PlaybackSpeeds.lastOrNull { it < current - 0.001f } ?: PlaybackSpeeds.first()
                    }
                manager.setPlaybackSpeed(next)
                seekState.toast?.cancel()
                seekState.toast =
                    Toast.makeText(s.context, "${next.toString().removeSuffix(".0")}x", Toast.LENGTH_SHORT)
                        .also { it.show() }
            }

            val handled =
                when (keyCode) {
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_K,
                    -> {
                        if (!isRepeat) a.onPlayPause()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-ARROW_SEEK_MS); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(ARROW_SEEK_MS); true }
                    KeyEvent.KEYCODE_J -> { seekBy(-JL_SEEK_MS); true }
                    KeyEvent.KEYCODE_L -> { seekBy(JL_SEEK_MS); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { adjustVolume(AudioManager.ADJUST_RAISE); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { adjustVolume(AudioManager.ADJUST_LOWER); true }
                    KeyEvent.KEYCODE_M -> { if (!isRepeat) toggleMute(); true }
                    KeyEvent.KEYCODE_F -> { if (!isRepeat) a.onFullscreenClick(); true }
                    KeyEvent.KEYCODE_C -> { if (!isRepeat) a.onSubtitleClick(); true }
                    KeyEvent.KEYCODE_MOVE_HOME -> { seekToFraction(0f); true }
                    KeyEvent.KEYCODE_MOVE_END -> {
                        // Land just before the end so the video doesn't instantly auto-advance.
                        val duration = screenState.duration
                        if (duration > 0L) {
                            val target = (duration - 100L).coerceAtLeast(0L)
                            seekState.pendingTargetMs = target
                            seekState.lastSeekAt = SystemClock.uptimeMillis()
                            a.onSeek(target)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_COMMA ->
                        if (shift) {
                            changeSpeed(faster = false)
                            true
                        } else if (!s.playerState.playWhenReady) {
                            a.onStepFrame(false)
                            true
                        } else {
                            false
                        }
                    KeyEvent.KEYCODE_PERIOD ->
                        if (shift) {
                            changeSpeed(faster = true)
                            true
                        } else if (!s.playerState.playWhenReady) {
                            a.onStepFrame(true)
                            true
                        } else {
                            false
                        }
                    KeyEvent.KEYCODE_N -> if (shift && !isRepeat) { a.onNext(); true } else false
                    KeyEvent.KEYCODE_P -> if (shift && !isRepeat) { a.onPrevious(); true } else false
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        seekToFraction((keyCode - KeyEvent.KEYCODE_0) / 10f)
                        true
                    }
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                        seekToFraction((keyCode - KeyEvent.KEYCODE_NUMPAD_0) / 10f)
                        true
                    }
                    else -> false
                }

            if (handled) {
                screenState.showControls = true
                screenState.onInteraction()
            }
            handled
        }

        PlayerKeyboardShortcuts.handler = handler
        onDispose {
            if (PlayerKeyboardShortcuts.handler === handler) PlayerKeyboardShortcuts.handler = null
            seekState.toast?.cancel()
        }
    }
}
