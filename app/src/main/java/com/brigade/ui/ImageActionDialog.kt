package com.brigade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.brigade.R
import com.brigade.content.ContentItem
import com.brigade.presentation.SlotBankState
import com.brigade.presentation.SlotContent
import com.brigade.presentation.SlotId

/**
 * What tapping an image in the browser offers: show it now, or park it in a slot (§6.1).
 *
 * Each slot row names its current occupant, so overwriting is a visible choice rather
 * than a surprise — assignment does not ask for confirmation, and mid-session it should
 * not have to.
 */
@Composable
fun ImageActionDialog(
    item: ContentItem,
    bank: SlotBankState,
    onShowNow: () -> Unit,
    onAssign: (SlotId) -> Unit,
    onHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Button(
                    onClick = onShowNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_show))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                bank.slots.forEach { slot ->
                    val occupant = when (val content = slot.content) {
                        is SlotContent.Filled -> content.displayName
                        is SlotContent.Missing -> content.path.fileName
                        SlotContent.Empty -> null
                    }

                    OutlinedButton(
                        onClick = { onAssign(slot.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.action_assign_slot, slot.id.index + 1))
                            if (occupant != null) {
                                Text(
                                    text = occupant,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                // When was this on the players' screen — the Séances screen's third view.
                OutlinedButton(
                    onClick = onHistory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_history))
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}
