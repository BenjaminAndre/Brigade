package com.brigade.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import com.brigade.R
import com.brigade.content.ContentId
import com.brigade.journal.Panel
import com.brigade.journal.PieSlice
import com.brigade.journal.Seances
import com.brigade.journal.SeancesData
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the screen re-reads the journal, so a session in progress counts up live. */
private const val REFRESH_MILLIS = 15_000L

/** Dates are French, like every string they sit in (§21) — as in the info panel. */
private val LOCALE: Locale = Locale.FRENCH

private enum class SeancesView { DayBars, DayPie, History }

/**
 * What the players had on screen, per day — a GM's tool, opened beside *Actualiser*.
 *
 * Three views over one journal: what a day was about (bars), what mattered most that day (the
 * pie), and when one note was on screen across days (its history). One set of day switches
 * applies to all three, so the GM can keep only campaign days and then flick between notes.
 *
 * The player display is untouched while this is open: presentation state is not read here
 * beyond the journal, let alone written.
 *
 * @param initialSubject open straight on this note's history — the browser's *Historique*.
 */
@Composable
fun SeancesScreen(
    load: suspend () -> SeancesData,
    onSetDayHidden: suspend (LocalDate, Boolean) -> Unit,
    initialSubject: Panel?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var data by remember { mutableStateOf<SeancesData?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf(if (initialSubject != null) SeancesView.History else SeancesView.DayBars) }
    var subject by remember { mutableStateOf(initialSubject) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var zoomed by remember { mutableStateOf<Panel?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val capture = rememberGraphicsLayer()
    val duration = rememberDurationFormat()

    LaunchedEffect(reloads) {
        while (true) {
            data = load()
            delay(REFRESH_MILLIS)
        }
    }

    val loaded = data
    val days = loaded?.visibleDays.orEmpty()
    // The last visible day by default — the one the GM most likely wants — and never a hidden
    // one, even after it is switched off while selected.
    val day = selectedDay?.takeIf { it in days } ?: days.lastOrNull()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // ---- Controls: not captured when sharing --------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.seances_open),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (view != SeancesView.History || subject != null) {
                    ViewButton(R.string.seances_view_bars, view == SeancesView.DayBars) { view = SeancesView.DayBars }
                    ViewButton(R.string.seances_view_pie, view == SeancesView.DayPie) { view = SeancesView.DayPie }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            val bitmap = capture.toImageBitmap()
                            shareImage(context, bitmap, context.getString(R.string.seances_share))
                        }
                    },
                    enabled = loaded != null && days.isNotEmpty(),
                ) { Text(stringResource(R.string.seances_share)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }

            DaySwitches(
                all = loaded?.byDay?.keys?.sorted().orEmpty(),
                hidden = loaded?.hidden.orEmpty(),
                onToggle = { toggled, hide ->
                    scope.launch {
                        onSetDayHidden(toggled, hide)
                        reloads++
                    }
                },
            )

            if (view != SeancesView.History && day != null) {
                DayStepper(
                    label = longDate(day),
                    canGoBack = days.indexOf(day) > 0,
                    canGoForward = days.indexOf(day) < days.lastIndex,
                    onBack = { selectedDay = days[days.indexOf(day) - 1] },
                    onForward = { selectedDay = days[days.indexOf(day) + 1] },
                )
            }

            // ---- The view: exactly what Partager sends -------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .drawWithContent {
                        capture.record { this@drawWithContent.drawContent() }
                        drawLayer(capture)
                    }
                    .background(MaterialTheme.colorScheme.background)
                    .padding(8.dp),
            ) {
                when {
                    loaded == null -> Unit
                    loaded.byDay.isEmpty() -> Message(stringResource(R.string.seances_empty))
                    days.isEmpty() -> Message(stringResource(R.string.seances_no_visible_day))

                    view == SeancesView.History && subject != null -> HistoryView(
                        subject = subject!!,
                        history = Seances.history(subject!!, loaded.byDay, days),
                        picture = loaded.pictures[subject!!],
                        duration = duration,
                    )

                    view == SeancesView.DayPie && day != null -> DayPieView(
                        day = day,
                        slices = Seances.pie(Seances.ranking(loaded.byDay[day].orEmpty())),
                        pictures = loaded.pictures,
                        duration = duration,
                        onSlice = { slice ->
                            // «Autres» has no single picture to show; the bars list all of it.
                            if (slice.panel == null) view = SeancesView.DayBars else zoomed = slice.panel
                        },
                    )

                    day != null -> DayBarsView(
                        day = day,
                        ranking = Seances.ranking(loaded.byDay[day].orEmpty()),
                        pictures = loaded.pictures,
                        duration = duration,
                        onPanel = { panel ->
                            subject = panel
                            view = SeancesView.History
                        },
                    )
                }
            }
        }
    }

    zoomed?.let { panel ->
        PictureDialog(
            panel = panel,
            picture = loaded?.pictures?.get(panel),
            onHistory = {
                subject = panel
                view = SeancesView.History
                zoomed = null
            },
            onDismiss = { zoomed = null },
        )
    }
}

// ---- Views ------------------------------------------------------------------------------

/** What a day was about: one bar per note or image, longest first. Tap one for its history. */
@Composable
private fun DayBarsView(
    day: LocalDate,
    ranking: List<Pair<Panel, Long>>,
    pictures: Map<Panel, ContentId>,
    duration: (Long) -> String,
    onPanel: (Panel) -> Unit,
) {
    val longest = ranking.firstOrNull()?.second?.coerceAtLeast(1L) ?: 1L

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ViewTitle(stringResource(R.string.seances_day_total, longDate(day), duration(ranking.sumOf { it.second })))

        ranking.forEach { (panel, millis) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPanel(panel) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Thumbnail(panel, pictures[panel], Modifier.size(40.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nameOf(panel),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((millis.toFloat() / longest).coerceIn(0.02f, 1f))
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    text = duration(millis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 56.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * What mattered most that day: at most six slices, each filled with its picture — or its name,
 * for a note that links none. A tap opens the picture large.
 */
@Composable
private fun DayPieView(
    day: LocalDate,
    slices: List<PieSlice>,
    pictures: Map<Panel, ContentId>,
    duration: (Long) -> String,
    onSlice: (PieSlice) -> Unit,
) {
    val total = slices.sumOf { it.millis }.coerceAtLeast(1L)
    val sweeps = slices.map { 360f * it.millis / total }
    val others = stringResource(R.string.seances_others)
    val fills = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.surfaceVariant,
    )
    val outline = MaterialTheme.colorScheme.background
    val labelColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    // One painter per slice, called the same number of times on every composition.
    val painters = slices.map { slice ->
        slice.panel?.let { pictures[it] }?.let { rememberAsyncImagePainter(it.value) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ViewTitle(stringResource(R.string.seances_day_total, longDate(day), duration(total)))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .pointerInput(slices) {
                        detectTapGestures { tap ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val radius = min(size.width, size.height) / 2f
                            if ((tap - c).getDistance() > radius) return@detectTapGestures
                            val angle = (Math.toDegrees(atan2((tap.y - c.y).toDouble(), (tap.x - c.x).toDouble())) + 450.0) % 360.0
                            var start = 0f
                            slices.forEachIndexed { index, slice ->
                                if (angle < start + sweeps[index]) return@detectTapGestures onSlice(slice)
                                start += sweeps[index]
                            }
                        }
                    },
            ) {
                val radius = min(size.width, size.height) / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                // From twelve o'clock, clockwise — how a pie is read.
                var start = -90f

                slices.forEachIndexed { index, slice ->
                    val sweep = sweeps[index]
                    val wedge = wedgePath(c, radius, start, sweep)
                    val painter = painters[index]

                    clipPath(wedge) {
                        drawRect(if (slice.panel == null) outline else fills[index % fills.size])
                        if (painter != null) drawCover(painter, wedgeBounds(c, radius, start, sweep))
                    }

                    if (painter == null && sweep > 24f) {
                        val name = slice.panel?.let(::nameOf) ?: others
                        val mid = Math.toRadians((start + sweep / 2).toDouble())
                        val at = Offset(c.x + radius * 0.6f * cos(mid).toFloat(), c.y + radius * 0.6f * sin(mid).toFloat())
                        val layout = measurer.measure(
                            name,
                            style = TextStyle(color = labelColor, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            constraints = Constraints(maxWidth = (radius * 0.7f).toInt().coerceAtLeast(1)),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        drawText(layout, topLeft = at - Offset(layout.size.width / 2f, layout.size.height / 2f))
                    }

                    drawPath(wedge, outline, style = Stroke(width = 3.dp.toPx()))
                    start += sweep
                }
            }

            // The legend: names for every slice, including the thin ones a label would not fit.
            Column(
                modifier = Modifier.weight(0.8f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slices.forEach { slice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSlice(slice) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val panel = slice.panel
                        if (panel != null) {
                            Thumbnail(panel, pictures[panel], Modifier.size(32.dp))
                        } else {
                            Spacer(Modifier.size(32.dp))
                        }
                        Text(
                            text = panel?.let(::nameOf) ?: others,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${100 * slice.millis / total} %",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * When was one note on screen: a bar for every visible day, empty days included — the gaps are
 * the answer too.
 */
@Composable
private fun HistoryView(
    subject: Panel,
    history: List<Pair<LocalDate, Long>>,
    picture: ContentId?,
    duration: (Long) -> String,
) {
    val longest = history.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Thumbnail(subject, picture, Modifier.size(56.dp))
            ViewTitle(stringResource(R.string.seances_history_title, nameOf(subject)))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            history.forEach { (day, millis) ->
                Column(
                    modifier = Modifier.fillMaxHeight().width(52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (millis > 0) {
                        Text(
                            text = duration(millis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight((0.8f * millis / longest).coerceAtLeast(0.005f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (millis > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                    Text(
                        text = SHORT_DATE.format(day),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ---- Pieces ------------------------------------------------------------------------------

/** One chip per recorded day; switched off, a day leaves every view. */
@Composable
private fun DaySwitches(
    all: List<LocalDate>,
    hidden: Set<LocalDate>,
    onToggle: (LocalDate, Boolean) -> Unit,
) {
    if (all.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.seances_days),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        all.forEach { day ->
            val shown = day !in hidden
            FilterChip(
                selected = shown,
                onClick = { onToggle(day, shown) },
                label = { Text(SHORT_DATE.format(day)) },
            )
        }
    }
}

@Composable
private fun DayStepper(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, enabled = canGoBack) {
            Text("‹", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onForward, enabled = canGoForward) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ViewButton(label: Int, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = stringResource(label),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The view's own heading, inside what is shared — so the image says what it is. */
@Composable
private fun ViewTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

/** The note's or image's picture, cropped square — or its initial, for a note linking none. */
@Composable
private fun Thumbnail(panel: Panel, picture: ContentId?, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            AsyncImage(
                model = picture.value,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = nameOf(panel).take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A pie slice, large: the picture whole, with a way on to its history. */
@Composable
private fun PictureDialog(
    panel: Panel,
    picture: ContentId?,
    onHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(nameOf(panel), style = MaterialTheme.typography.titleMedium)
                if (picture != null) {
                    AsyncImage(
                        model = picture.value,
                        contentDescription = nameOf(panel),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
                Row(modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onHistory) { Text(stringResource(R.string.action_history)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}

// ---- Helpers -----------------------------------------------------------------------------

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", LOCALE)

/** «Samedi 26 septembre 2026». */
private fun longDate(day: LocalDate): String =
    DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", LOCALE).format(day).replaceFirstChar { it.titlecase(LOCALE) }

/** A note by its name without extension — the character's name — and an image by its file name. */
private fun nameOf(panel: Panel): String = when (panel) {
    is Panel.Note -> panel.path.fileName.substringBeforeLast('.')
    is Panel.Image -> panel.path.fileName
    Panel.Info, Panel.Black -> ""
}

@Composable
private fun rememberDurationFormat(): (Long) -> String {
    val hours = stringResource(R.string.seances_duration_hours)
    val minutes = stringResource(R.string.seances_duration_minutes)
    val seconds = stringResource(R.string.seances_duration_seconds)
    return remember(hours, minutes, seconds) {
        { millis -> Seances.formatDuration(millis, hours, minutes, seconds, LOCALE) }
    }
}

private fun wedgePath(c: Offset, radius: Float, start: Float, sweep: Float): Path = Path().apply {
    moveTo(c.x, c.y)
    // A whole-day slice is a full circle; arcTo cannot draw exactly 360°.
    arcTo(Rect(c, radius), start, min(sweep, 359.99f), forceMoveTo = false)
    close()
}

/** The bounding box of a wedge, so its picture is centred on the wedge rather than the pie. */
private fun wedgeBounds(c: Offset, radius: Float, start: Float, sweep: Float): Rect {
    var left = c.x
    var top = c.y
    var right = c.x
    var bottom = c.y
    val steps = 24
    for (i in 0..steps) {
        val angle = Math.toRadians((start + sweep * i / steps).toDouble())
        val x = c.x + radius * cos(angle).toFloat()
        val y = c.y + radius * sin(angle).toFloat()
        left = min(left, x)
        top = min(top, y)
        right = max(right, x)
        bottom = max(bottom, y)
    }
    return Rect(left, top, right, bottom)
}

/** Draws [painter] to cover [area], cropping the overflow — `ContentScale.Crop`, by hand. */
private fun DrawScope.drawCover(painter: Painter, area: Rect) {
    val source = painter.intrinsicSize
    if (source.isUnspecified || source.width <= 0f || source.height <= 0f) return
    val scale = max(area.width / source.width, area.height / source.height)
    val drawn = Size(source.width * scale, source.height * scale)
    translate(
        left = area.left + (area.width - drawn.width) / 2f,
        top = area.top + (area.height - drawn.height) / 2f,
    ) {
        with(painter) { draw(drawn) }
    }
}

/**
 * Hands [image] to Android's share sheet as a PNG — Discord, a messenger, wherever the GM
 * picks. Brigade itself sends nothing anywhere: it has no network permission and wants none.
 */
private suspend fun shareImage(context: Context, image: ImageBitmap, chooserTitle: String) {
    val uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "seance.png")
        // A software copy: the captured bitmap may be a hardware one, which cannot be read
        // back pixel by pixel to compress.
        val bitmap = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}$SHARE_AUTHORITY_SUFFIX", file)
    }

    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, chooserTitle))
}

/** Must match `res/xml/share_paths.xml` and the provider's authority in the manifest. */
private const val SHARE_DIRECTORY = "share"
private const val SHARE_AUTHORITY_SUFFIX = ".share"
