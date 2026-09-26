package com.brigade.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.brigade.R
import com.brigade.content.ContentId
import com.brigade.content.ContentItem
import com.brigade.presentation.SlotBankState
import com.brigade.presentation.SlotContent
import com.brigade.ui.theme.ImageCaptionScrim
import com.brigade.ui.theme.OnImageCaption

/**
 * The content browser.
 *
 * Tapping an image opens the action menu; it never presents anything by itself. That is
 * what makes §16 Trap 4 structural rather than a promise: browsing simply has no path to
 * presentation state.
 */
@Composable
fun ContentGrid(
    browsing: BrowsingState,
    bank: SlotBankState,
    onEnterFolder: (ContentItem) -> Unit,
    onImageTapped: (ContentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        state = rememberLazyGridState(),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(browsing.folders, key = { "d:" + it.id.value }) { folder ->
            FolderCell(folder, onClick = { onEnterFolder(folder) })
        }

        items(browsing.documents, key = { "n:" + it.id.value }) { note ->
            NoteCell(
                item = note,
                image = browsing.noteImages[note.id],
                assignedSlot = bank.slotNumberFor(note),
                onClick = { onImageTapped(note) },
            )
        }

        items(browsing.images, key = { "f:" + it.id.value }) { image ->
            ImageCell(
                item = image,
                assignedSlot = bank.slotNumberFor(image),
                onClick = { onImageTapped(image) },
            )
        }

        if (browsing.isEmpty && !browsing.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.browser_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

/**
 * Which slot holds [item], if any.
 *
 * Matched on display name rather than id: a slot stores a campaign-relative *path*, and the
 * resolved id it carries for a note is the note's first **image**, not the note itself. Name
 * matching keeps a note and the image it links from badging each other.
 */
private fun SlotBankState.slotNumberFor(item: ContentItem): Int? = slots.firstOrNull { slot ->
    (slot.content as? SlotContent.Filled)?.path?.fileName == item.displayName
}?.id?.index?.plus(1)

/**
 * A note in the browser, drawn with the image recalling it would present.
 *
 * Until its note has been read — or when it links no image — the cell shows the name alone,
 * so the grid is usable the moment the folder lists and thumbnails fill in behind. With an
 * image it reads like an image cell, but keeps the note's badge and tinted border: tapping it
 * presents a note, bar and all, not just the picture.
 */
@Composable
private fun NoteCell(
    item: ContentItem,
    image: ContentId?,
    assignedSlot: Int?,
    onClick: () -> Unit,
) {
    val name = item.displayName.substringBeforeLast('.')

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            // surfaceVariant, not black, behind a thumbnail still loading: a black square in a
            // paper grid reads as a hole.
            .background(if (image != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(
                model = image.value,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )

            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = OnImageCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(ImageCaptionScrim)
                    // Room on the right for the badge, which sits over this strip.
                    .padding(start = 6.dp, end = 28.dp, top = 3.dp, bottom = 3.dp),
            )
        } else {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }

        Text(
            text = stringResource(R.string.browser_note_badge),
            style = MaterialTheme.typography.labelSmall,
            color = if (image != null) OnImageCaption else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )

        if (assignedSlot != null) {
            Text(
                text = assignedSlot.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FolderCell(item: ContentItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImageCell(
    item: ContentItem,
    assignedSlot: Int?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.id.value,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )

        // Tells the GM at a glance which images are already in the bank, so assigning a
        // seventh does not silently displace one they meant to keep.
        if (assignedSlot != null) {
            Text(
                text = assignedSlot.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Text(
            text = item.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = OnImageCaption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(ImageCaptionScrim)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
