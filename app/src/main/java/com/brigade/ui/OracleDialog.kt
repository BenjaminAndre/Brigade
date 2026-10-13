package com.brigade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.brigade.R
import com.brigade.content.ContentItem
import com.brigade.content.Oracle
import kotlinx.coroutines.launch

/**
 * The oracle: pick a table from the campaign's `Oracles/` folder, read one entry, reroll or
 * leave.
 *
 * GM-only, like the browser. Nothing here touches presentation state, so a roll can never
 * reach the players' screen — the GM decides what, if anything, to say about it.
 *
 * The tables are listed afresh each time it opens, and a table's entries are read when it is
 * chosen, so an edit made in Obsidian beside Brigade is in the very next roll.
 */
@Composable
fun OracleDialog(
    loadTables: suspend () -> List<ContentItem>?,
    loadEntries: suspend (ContentItem) -> List<String>,
    onDismiss: () -> Unit,
) {
    // null while loading; an empty list after loading when the folder is missing or empty.
    var tables by remember { mutableStateOf<List<ContentItem>?>(null) }
    var folderMissing by remember { mutableStateOf(false) }
    var table by remember { mutableStateOf<ContentItem?>(null) }
    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    var rolls by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val loaded = loadTables()
        folderMissing = loaded == null
        tables = loaded.orEmpty()
    }

    fun roll() {
        result = Oracle.roll(entries)
        rolls++
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val chosen = table
                if (chosen == null) {
                    // ---- Which table ----------------------------------------------------------
                    Text(stringResource(R.string.oracle_title), style = MaterialTheme.typography.titleMedium)

                    val listed = tables
                    when {
                        listed == null -> Unit
                        folderMissing -> Hint(stringResource(R.string.oracle_no_folder))
                        listed.isEmpty() -> Hint(stringResource(R.string.oracle_empty_folder))
                        else -> Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listed.forEach { item ->
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            entries = loadEntries(item)
                                            table = item
                                            rolls = 0
                                            roll()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(item.displayName.substringBeforeLast('.'))
                                }
                            }
                        }
                    }

                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_close))
                    }
                } else {
                    // ---- The roll -------------------------------------------------------------
                    Text(
                        text = chosen.displayName.substringBeforeLast('.'),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Text(
                        text = result ?: stringResource(R.string.oracle_empty_table),
                        style = if (result != null) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )

                    // A reroll may legitimately land on the same line. The count is what shows
                    // the button did something.
                    if (result != null) {
                        Text(
                            text = stringResource(R.string.oracle_roll_count, rolls),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { table = null; result = null }) {
                            Text(stringResource(R.string.oracle_tables))
                        }
                        Button(
                            onClick = { roll() },
                            enabled = entries.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.oracle_reroll))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
