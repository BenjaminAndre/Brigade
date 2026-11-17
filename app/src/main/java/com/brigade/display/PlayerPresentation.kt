package com.brigade.display

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.brigade.presentation.PresentationState
import com.brigade.presentation.TimerStyle
import com.brigade.render.PlayerImageModel
import com.brigade.render.PresentationSurface
import kotlinx.coroutines.flow.StateFlow

/**
 * The player-facing window: a fullscreen [PresentationSurface] on the external display.
 *
 * It knows nothing about folders, thumbnails, slots or navigation (§11). It receives
 * presentation state and draws it.
 *
 * ### Why this class implements the owner interfaces by hand
 *
 * A `ComposeView` inside a `Dialog` crashes with *"ViewTreeLifecycleOwner not found
 * from DecorView"*, because a Dialog's decor view carries no lifecycle or saved-state
 * owners. A `Presentation` is a Dialog, so it has to supply them itself.
 *
 * ### Two rules that are load-bearing
 *
 * 1. **`setViewTreeViewModelStoreOwner` is deliberately not called.** Without it,
 *    a stray `viewModel()` inside the player composition fails loudly during
 *    development instead of quietly constructing a *second* independent state holder —
 *    which is precisely the "two independent pieces of business logic" §5 forbids, and
 *    it is one innocent-looking line away.
 *
 * 2. **This class is single-use.** A [LifecycleRegistry] cannot be reused once it has
 *    reached `DESTROYED`, so after [dismiss] the instance must be thrown away and a new
 *    one constructed. Reusing it yields a window that is silently blank.
 */
class PlayerPresentation(
    outerContext: Context,
    display: Display,
    private val state: StateFlow<PresentationState>,
    private val imageModel: StateFlow<PlayerImageModel>,
    private val timerStyle: StateFlow<TimerStyle>,
) : Presentation(outerContext, display), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // The players must not be able to dismiss what they cannot touch.
        setCancelable(false)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Output-only: never take focus or touch away from the tablet (§11).
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowInsetsControllerCompat(this, decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        setContentView(
            ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@PlayerPresentation)
                setViewTreeSavedStateRegistryOwner(this@PlayerPresentation)
                setContent {
                    val current by state.collectAsStateWithLifecycle()
                    // The SAME model instance the GM preview uses, so both windows resolve
                    // to one Coil cache entry and one decode.
                    val model by imageModel.collectAsStateWithLifecycle()
                    val style by timerStyle.collectAsStateWithLifecycle()
                    PresentationSurface(current, Modifier.fillMaxSize(), model, style)
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onStop()
    }
}
