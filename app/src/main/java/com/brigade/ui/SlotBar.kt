package com.brigade.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.brigade.R
import com.brigade.presentation.ActiveControl
import com.brigade.presentation.Slot
import com.brigade.presentation.SlotBankState
import com.brigade.presentation.SlotContent
import com.brigade.presentation.SlotId
import com.brigade.presentation.TimerOverlay
import java.util.Locale
import kotlinx.coroutines.delay

private val SLOT_ROW_HEIGHT = 76.dp
private val ACTION_ROW_HEIGHT = 48.dp

/**
 * The control bar, in two rows (§6.1).
 *
 *  - **The six slots**: *what* is shown.
 *  - **The actions**: INFO, SABLIER, ORACLE, SÉANCES — everything that is not a choice of
 *    picture. Equal widths, so a new action is one more button and no layout change.
 *
 * Everything here is sized for use in dim light, mid-sentence, without looking down. The
 * highlight marks the slot that is *live*, which is a readout of presentation state, not of
 * whatever was tapped last.
 */
@Composable
fun SlotBar(
    bank: SlotBankState,
    active: ActiveControl,
    timer: TimerOverlay?,
    onRecall: (SlotId) -> Unit,
    onClear: (SlotId) -> Unit,
    onToggleInfo: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenOracle: () -> Unit,
    onOpenSeances: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.height(SLOT_ROW_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bank.slots.forEach { slot ->
                SlotCell(
                    slot = slot,
                    isLive = active is ActiveControl.Slot && active.id.index == slot.id.index,
                    onRecall = { onRecall(slot.id) },
                    onClear = { onClear(slot.id) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }

        Row(
            modifier = Modifier.height(ACTION_ROW_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Always enabled, even with no campaign info configured — INFO is also the blank
            // control, and "nothing specific, I'm preparing" is exactly when a GM reaches for it.
            ActionButton(
                label = stringResource(R.string.control_info),
                lit = active is ActiveControl.Info,
                onClick = onToggleInfo,
            )

            // The players get a stick, the GM gets the number. Text is free on this side,
            // and knowing it is 1:47 rather than "about a third left" is worth having.
            ActionButton(
                label = rememberRemainingLabel(timer) ?: stringResource(R.string.control_timer),
                lit = timer != null,
                litWithTertiary = true,
                onClick = onOpenTimer,
            )

            ActionButton(label = stringResource(R.string.control_oracle), lit = false, onClick = onOpenOracle)

            ActionButton(label = stringResource(R.string.control_seances), lit = false, onClick = onOpenSeances)
        }
    }
}

/** One action: tonal at rest, filled while what it controls is on. */
@Composable
private fun RowScope.ActionButton(
    label: String,
    lit: Boolean,
    onClick: () -> Unit,
    litWithTertiary: Boolean = false,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = when {
            !lit -> ButtonDefaults.filledTonalButtonColors()
            litWithTertiary -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            )
            else -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * `M:SS` while the incense burns, null when none is lit.
 *
 * Ticks twice a second and recomposes only this one button. The player surface is unaffected:
 * it reads the same [TimerOverlay] but derives the stick's length in its draw phase.
 */
@Composable
private fun rememberRemainingLabel(timer: TimerOverlay?): String? {
    if (timer == null) return null

    var label by remember(timer) { mutableStateOf(formatRemaining(timer)) }
    LaunchedEffect(timer) {
        while (true) {
            label = formatRemaining(timer)
            delay(500)
        }
    }
    return label
}

private fun formatRemaining(timer: TimerOverlay): String {
    val totalSeconds = (timer.remainingMillisAt(System.nanoTime()) + 999L) / 1000L
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotCell(
    slot: Slot,
    isLive: Boolean,
    onRecall: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val number = slot.id.index + 1
    val content = slot.content

    val description = when (content) {
        SlotContent.Empty -> stringResource(R.string.slot_empty, number)
        is SlotContent.Missing -> stringResource(R.string.slot_missing)
        is SlotContent.Filled -> stringResource(R.string.slot_filled, number, content.displayName)
    }

    val borderColor = when {
        isLive -> MaterialTheme.colorScheme.primary
        content is SlotContent.Missing -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (isLive) 3.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            // Long-press clears. Assigning overwrites without confirmation, so the only
            // destructive gesture is the one that is hard to trigger by accident.
            // The label is what makes an otherwise invisible gesture announceable.
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.action_clear_slot),
                onLongClick = onClear,
                onClick = onRecall,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (content) {
            SlotContent.Empty -> Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is SlotContent.Missing -> Text(
                text = stringResource(R.string.slot_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            is SlotContent.Filled -> {
                if (content.imageId != null) {
                    AsyncImage(
                        model = content.imageId.value,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    // A note that links no image. The slot is still perfectly usable — it
                    // fills the GM bar and blanks the players — so it shows its name rather
                    // than reading as broken.
                    Text(
                        text = content.displayName.substringBeforeLast('.'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )

                if (content.isNote) {
                    Text(
                        text = stringResource(R.string.browser_note_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
