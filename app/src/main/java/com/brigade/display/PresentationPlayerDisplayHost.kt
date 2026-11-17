package com.brigade.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.brigade.presentation.PresentationState
import com.brigade.presentation.TimerStyle
import com.brigade.render.PlayerImageModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PlayerDisplayHost] backed by Android's multi-display `Presentation` mechanism.
 *
 * Lives on the application graph so that the window and the `DisplayListener`
 * registration survive Activity recreation — which on a DeX tablet happens whenever the
 * window is dragged or the device is rotated.
 */
class PresentationPlayerDisplayHost(
    private val appContext: Context,
    private val state: StateFlow<PresentationState>,
    private val imageModel: StateFlow<PlayerImageModel>,
    private val timerStyle: StateFlow<TimerStyle>,
) : PlayerDisplayHost {

    private val _status = MutableStateFlow<PlayerDisplayStatus>(PlayerDisplayStatus.Absent)
    override val status: StateFlow<PlayerDisplayStatus> = _status.asStateFlow()

    private var activity: ComponentActivity? = null
    private var presentation: PlayerPresentation? = null
    private var boundDisplayId: Int? = null
    private var listenerRegistered = false

    private val displayManager: DisplayManager
        get() = appContext.getSystemService(DisplayManager::class.java)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = sync()
        override fun onDisplayRemoved(displayId: Int) = sync()

        /** Also fires for rotation and resolution changes on an already-bound display. */
        override fun onDisplayChanged(displayId: Int) = sync()
    }

    override fun attach(activity: ComponentActivity) {
        if (this.activity === activity) {
            sync()
            return
        }

        dismissPresentation()
        this.activity = activity

        if (!listenerRegistered) {
            displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            listenerRegistered = true
        }

        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                // The first sync waits for ON_START rather than running here: a window
                // shown from onCreate, before the Activity's own window has a token,
                // throws BadTokenException. addObserver replays up to the current state,
                // so an already-started Activity syncs immediately anyway.
                override fun onStart(owner: LifecycleOwner) = sync()

                // Detach on ON_DESTROY, not ON_STOP. In DeX the GM will routinely put
                // another window in front of Brigade; the players' image must not go
                // black because the GM opened a PDF. A Dialog window stays valid while
                // its Activity is stopped — the only hazard is outliving onDestroy,
                // which this prevents.
                override fun onDestroy(owner: LifecycleOwner) = detach()
            },
        )
    }

    override fun detach() {
        if (listenerRegistered) {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
            listenerRegistered = false
        }
        dismissPresentation()
        activity = null
        _status.value = PlayerDisplayStatus.Absent
    }

    private fun sync() {
        val activity = this.activity ?: return
        val selfDisplayId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        val target = pickPlayerDisplay(candidates(activity), selfDisplayId)

        if (target == null) {
            dismissPresentation()
            _status.value = PlayerDisplayStatus.Absent
            return
        }

        if (boundDisplayId != target.id) {
            dismissPresentation()

            val display = displayManager.getDisplay(target.id)
            if (display == null) {
                _status.value = PlayerDisplayStatus.Absent
                return
            }

            val created = PlayerPresentation(activity, display, state, imageModel, timerStyle)
            created.setOnDismissListener {
                // Presentation auto-dismisses when its display is removed; keep our
                // own references honest when that happens behind our back.
                if (presentation === created) {
                    presentation = null
                    boundDisplayId = null
                }
            }

            try {
                created.show()
            } catch (_: WindowManager.InvalidDisplayException) {
                // The display vanished between selection and show. Genuinely happens
                // during hotplug; the next DisplayListener callback will re-sync.
                _status.value = PlayerDisplayStatus.Absent
                return
            } catch (_: WindowManager.BadTokenException) {
                // The hosting Activity is not in a state that can own a window. Never
                // worth crashing a session over; ON_START will try again.
                _status.value = PlayerDisplayStatus.Absent
                return
            }

            presentation = created
            boundDisplayId = target.id
        }

        // Same display, new metrics (rotation or resolution change): republish status
        // and let Compose re-measure. Tearing the window down and rebuilding it here
        // would produce a visible black flash at the table.
        _status.value = PlayerDisplayStatus.Attached(
            displayId = target.id,
            name = target.name,
            widthPx = target.widthPx,
            heightPx = target.heightPx,
        )
    }

    private fun candidates(context: Context): List<DisplayInfo> {
        val manager = displayManager

        // DISPLAY_CATEGORY_PRESENTATION already filters to displays the platform
        // considers suitable, most-preferred first. Kept as a preference rather than a
        // filter, so an HDMI display that does not advertise the flag is still usable.
        val preferred = manager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()

        return manager.displays.map { display ->
            val (width, height) = sizeOf(context, display)
            DisplayInfo(
                id = display.displayId,
                name = display.name.orEmpty(),
                widthPx = width,
                heightPx = height,
                presentationFlag = display.displayId in preferred ||
                    (display.flags and Display.FLAG_PRESENTATION) != 0,
                isPrivate = (display.flags and Display.FLAG_PRIVATE) != 0,
            )
        }
    }

    /**
     * Pixel size of [display].
     *
     * `Display.getMode()` first, deliberately. It reports *that display's* own native
     * resolution, which is exactly what the player surface is.
     *
     * `maximumWindowMetrics` on a display context looks like the more modern route, and
     * it was the primary source here until it caused a real bug: it reports the bounds
     * the **context** would be given, and for a simulated or freshly-attached display
     * that can silently be the host device's own screen. It does not throw when it is
     * wrong, so the fallback never fired — it just returned a plausible portrait size,
     * which drove the preview's aspect ratio until the preview swallowed the whole GM
     * screen. It stays only as a fallback.
     *
     * Returning 0×0 is safe: [PlayerDisplayStatus.Attached.aspectRatio] falls back to
     * the default ratio rather than dividing by zero.
     */
    private fun sizeOf(context: Context, display: Display): Pair<Int, Int> {
        val mode = display.mode
        if (mode != null && mode.physicalWidth > 0 && mode.physicalHeight > 0) {
            return mode.physicalWidth to mode.physicalHeight
        }

        return runCatching {
            val bounds = context.createDisplayContext(display)
                .getSystemService(WindowManager::class.java)
                .maximumWindowMetrics
                .bounds
            bounds.width() to bounds.height()
        }.getOrDefault(0 to 0)
    }

    private fun dismissPresentation() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        boundDisplayId = null
    }
}
