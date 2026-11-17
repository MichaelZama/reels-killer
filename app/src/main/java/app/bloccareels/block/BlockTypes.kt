package app.bloccareels.block

import app.bloccareels.data.CustomMedia
import app.bloccareels.data.Illustration
import app.bloccareels.data.MediaKind
import app.bloccareels.detect.Detection
import app.bloccareels.detect.ExitAction

/** States of [BlockController]; the transitions are listed there. */
enum class BlockState {
    /** Nothing in progress: no short videos on screen. */
    IDLE,

    /**
     * A single short video is open, for example a reel sent in a chat. It plays in full:
     * the block starts at the first swipe, or when the video's fingerprint changes.
     */
    OBSERVING,

    /** The block screen is shown. The audio is muted if that setting is on and no custom video plays. */
    BLOCKED,

    /**
     * The user tapped Continue: short videos are usable for a few seconds
     * (`Settings.truceSeconds`), then the screen is checked again.
     */
    TRUCE,
}

/** What the controller needs to know about a host app: block screen subtitle and what "Exit" does. */
data class AppMeta(val label: String, val exitAction: ExitAction)

/** Content of the block screen. */
data class OverlayModel(
    val message: String,
    val subtitle: String,
    val illustration: Illustration,
    val blocksToday: Int,
    /** 0 = "Continue" is enabled right away. */
    val waitSeconds: Int,
    val showEscalationHint: Boolean,
    /** Image, GIF or video picked by the user; null = the [illustration] drawing. */
    val customMedia: CustomMedia? = null,
) {
    /** A custom video has its own sound: in that case the media stream is not muted. */
    val playsOwnAudio: Boolean get() = customMedia?.kind == MediaKind.VIDEO
}

/** Commands the controller sends to Android. */
interface BlockActions {
    fun showOverlay(model: OverlayModel)
    fun hideOverlay()
    fun muteAudio()
    fun restoreAudio()
    fun performExit(action: ExitAction)

    /** Evaluates the rules synchronously. */
    fun evaluateNow(): Detection
}

/** Daily counters, persisted by the repository. */
interface Counters {
    /** Increments today's blocks and returns the new total. */
    fun onBlockShown(): Int
    fun onContinue()
    fun onExit()
}
