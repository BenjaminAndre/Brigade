package com.brigade.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brigade.appGraph
import com.brigade.ui.theme.BrigadeTheme

/**
 * Behind a three-button navigation bar only; gesture navigation draws no scrim. The two themes'
 * backgrounds, translucent, so the bar reads as part of the page rather than a grey stripe.
 */
private val NAV_SCRIM_PAPIER = AndroidColor.argb(0xE6, 0xE4, 0xD6, 0xBF)
private val NAV_SCRIM_ENCRE = AndroidColor.argb(0xCC, 0x1A, 0x16, 0x11)

class GmActivity : ComponentActivity() {

    private val graph by lazy { appGraph }

    private val pickCampaignFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                takeGrant(uri)
                graph.setRoot(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first frame, so it is never laid out without edge-to-edge and then
        // jumps. The effect in setContent re-applies it when the tablet changes mode.
        enableEdgeToEdge()

        // Attached here and detached on ON_DESTROY by the host itself, so the player
        // window survives the GM backgrounding the app — which in DeX happens whenever
        // they bring another window forward.
        graph.playerDisplay.attach(this)

        setContent {
            val dark = isSystemInDarkTheme()

            // Re-applied whenever the tablet switches between light and dark. The manifest
            // handles uiMode itself, so the Activity is not recreated and the call in onCreate
            // would leave the status-bar icons in the old mode — dark icons on Encre's ink,
            // light ones on Papier's paper. The theme below flips on its own.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                    ) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        NAV_SCRIM_PAPIER,
                        NAV_SCRIM_ENCRE,
                    ) { dark },
                )
                onDispose {}
            }

            BrigadeTheme(dark = dark) {
                val gmViewModel: GmViewModel = viewModel(factory = GmViewModel.Factory)

                val browsing by gmViewModel.browsing.collectAsStateWithLifecycle()
                // Note both this composition and the player window collect the SAME
                // StateFlow instance from the application graph. That is the whole of
                // "one presentation state, two render targets" (§5).
                val presentation by graph.presentation.state.collectAsStateWithLifecycle()
                val bank by graph.slots.state.collectAsStateWithLifecycle()
                val displayStatus by graph.playerDisplay.status.collectAsStateWithLifecycle()
                // Same instance the player window collects — see PlayerImageModel.
                val imageModel by graph.imageModel.collectAsStateWithLifecycle()
                val campaign by graph.campaign.collectAsStateWithLifecycle()
                val campaignDateIso by graph.campaignDateIso.collectAsStateWithLifecycle()
                val slotWriteFailed by graph.slotWriteFailed.collectAsStateWithLifecycle()
                val recapProblem by graph.recapProblem.collectAsStateWithLifecycle()

                BackHandler(enabled = browsing.canGoUp) { gmViewModel.up() }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GmScreen(
                        browsing = browsing,
                        presentation = presentation,
                        bank = bank,
                        displayStatus = displayStatus,
                        imageModel = imageModel,
                        campaignDateIso = campaignDateIso,
                        campaign = campaign,
                        slotWriteFailed = slotWriteFailed,
                        recapProblem = recapProblem,
                        onChooseFolder = { pickCampaignFolder.launch(null) },
                        onJumpTo = gmViewModel::jumpTo,
                        // Actualiser re-reads Campagne.md as well as the folder listing:
                        // the GM edits both in Obsidian and expects one button to catch up.
                        onRefresh = {
                            gmViewModel.refresh()
                            graph.refreshCampaign()
                        },
                        onEnterFolder = gmViewModel::enter,
                        onShowNow = { item -> graph.showNow(item, browsing.folderPath) },
                        onAssign = { slot, item ->
                            graph.assignSlot(slot, item, browsing.folderNames)
                        },
                        onRecall = graph::recall,
                        onClearSlot = graph::clearSlot,
                        onToggleInfo = graph::toggleInfo,
                        onStartTimer = graph::startTimer,
                        onExtendTimer = graph::extendTimer,
                        onClearTimer = graph::clearTimer,
                        onPanZoom = graph::panZoom,
                        onResetViewport = graph::resetViewport,
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                    )
                }
            }
        }
    }

    /**
     * Re-reads `Campagne.md` whenever the GM surface comes forward.
     *
     * This is what makes an edit in Obsidian appear by itself: in DeX the GM switches to
     * Obsidian, changes the in-world date, switches back, and Brigade has already caught up.
     * A filesystem watch would be the "proper" answer, but a `ContentObserver` over the
     * Storage Access Framework is unreliable across document providers, and this catches
     * every case that actually happens at a table for one small file read.
     */
    override fun onStart() {
        super.onStart()
        graph.refreshCampaign()
    }

    /**
     * Takes read **and** write on the tree.
     *
     * Write is needed only for `.brigade/slots.json` (§6.1). If a provider grants read
     * alone, fall back to that: the bank then cannot be persisted, which the UI reports,
     * but everything else keeps working rather than the folder being rejected outright.
     */
    private fun takeGrant(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, readWrite) }
            .onFailure {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
    }
}
