package com.fugazi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fugazi.app.journal.ThoughtStore
import com.fugazi.app.sense.DaySignals
import com.fugazi.app.sense.SignalStore
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A warm page for today, with earlier days a tap away along the top. It saves as you type;
 * there's no button to press and nothing to fill in.
 *
 * The page itself is the scrolling surface, sized to the space above the keyboard, so a long
 * entry scrolls inside it and the line you're typing always stays in view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThoughtsScreen() {
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    var text by remember(day) { mutableStateOf(ThoughtStore.read(day)) }
    var saved by remember(day) { mutableStateOf(text) }
    val days by ThoughtStore.days.collectAsState()
    val signals by SignalStore.days.collectAsState()
    val warm = warmColors()
    val typing = WindowInsets.isImeVisible

    // Moving to another day cancels the pending save below, so flush it first.
    fun open(d: LocalDate) {
        if (text != saved) ThoughtStore.writeNow(day, text)
        day = d
    }

    // Same when leaving the tab altogether.
    val latest by rememberUpdatedState(Triple(day, text, saved))
    DisposableEffect(Unit) {
        onDispose { latest.let { (d, t, s) -> if (t != s) ThoughtStore.writeNow(d, t) } }
    }

    // Save a moment after typing stops, rather than on every keystroke.
    LaunchedEffect(day, text) {
        if (text == saved) return@LaunchedEffect
        delay(800)
        ThoughtStore.write(day, text)
        saved = text
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(warm.page)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // While typing, the page gets the room: the header folds to one line.
        if (typing) {
            Text(
                if (day == today) "Today · ${day.format(LONG)}" else day.format(LONG),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = warm.accent,
            )
        } else {
        // Header: a sunrise that greets you.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(warm.sunrise))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                if (day == today) greeting() else "Looking back",
                color = warm.onSunrise.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                day.format(LONG),
                color = warm.onSunrise,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            signals[day.toString()]?.let { SignalLine(it, warm.onSunrise) }
        }

        // Earlier days along the top — today first, then newest to oldest.
        val chips = (listOf(today) + days.filter { it != today }).distinct()
        if (chips.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(chips, key = { it.toString() }) { d ->
                    DayChip(d, today, selected = d == day, warm = warm) { open(d) }
                }
            }
        }
        }

        // The page. It takes all the room that's left and scrolls on its own.
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, warm.edge, RoundedCornerShape(24.dp)),
            placeholder = {
                Text(prompt(day), style = PAGE, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            },
            textStyle = PAGE.copy(color = MaterialTheme.colorScheme.onSurface),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = warm.paper,
                unfocusedContainerColor = warm.paper,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = warm.accent,
            ),
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            Text(
                if (words == 0) "" else "$words ${if (words == 1) "word" else "words"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (text.isNotBlank()) Text(
                if (text == saved) "saved ♥" else "saving…",
                style = MaterialTheme.typography.labelMedium,
                color = if (text == saved) warm.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayChip(d: LocalDate, today: LocalDate, selected: Boolean, warm: Warm, onClick: () -> Unit) {
    val first = remember(d) { ThoughtStore.read(d).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
    Column(
        Modifier
            .widthIn(min = 72.dp, max = 160.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Brush.linearGradient(warm.sunrise) else Brush.linearGradient(listOf(warm.paper, warm.paper)))
            .border(1.dp, if (selected) Color.Transparent else warm.edge, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val fg = if (selected) warm.onSunrise else MaterialTheme.colorScheme.onSurface
        Text(
            if (d == today) "Today" else d.format(CHIP),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
        if (first.isNotBlank()) Text(
            first,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = fg.copy(alpha = 0.75f),
        )
    }
}

/** What the phone saw that day, in one quiet line — context for rereading, not a score. */
@Composable
private fun SignalLine(s: DaySignals, color: Color) {
    val parts = listOfNotNull(
        s.wake?.let { "woke ${it.take(5)}" },
        s.steps?.let { "%,d steps".format(it) },
        s.longestWalkMin?.takeIf { it > 0 }?.let { "walked $it min" },
        s.tracks?.takeIf { it > 0 }?.let { "skipped ${s.skips ?: 0}/$it songs" },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = color.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun greeting(now: LocalTime = LocalTime.now()) = when (now.hour) {
    in 4..11 -> "Good morning ☀"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening ☾"
    else -> "Still up? ☾"
}

/** A different gentle opener each day, so the blank page never feels like a form. */
private fun prompt(day: LocalDate) = PROMPTS[(day.toEpochDay() % PROMPTS.size).toInt()]

private val PROMPTS = listOf(
    "What's on your mind?",
    "What made you smile today?",
    "Tell me about today, however it went.",
    "What did you notice today?",
    "Who did you meet, and how did it feel?",
    "What are you proud of right now?",
    "What's been sitting with you?",
)

/** Warm colours that don't follow the wallpaper: this page should always feel like morning light. */
private data class Warm(
    val page: Color,
    val paper: Color,
    val edge: Color,
    val accent: Color,
    val sunrise: List<Color>,
    val onSunrise: Color,
)

@Composable
private fun warmColors(): Warm = if (isSystemInDarkTheme()) Warm(
    page = Color(0xFF1A1414),
    paper = Color(0xFF241C1B),
    edge = Color(0xFF3A2C2A),
    accent = Color(0xFFFFB38A),
    sunrise = listOf(Color(0xFF7A3B52), Color(0xFFB0553F), Color(0xFFC98A3C)),
    onSunrise = Color(0xFFFFF4EC),
) else Warm(
    page = Color(0xFFFFF8F2),
    paper = Color(0xFFFFFFFF),
    edge = Color(0xFFF2DDD0),
    accent = Color(0xFFD9634A),
    sunrise = listOf(Color(0xFFFFB199), Color(0xFFFF8E7F), Color(0xFFC89BE8)),
    onSunrise = Color(0xFF3B1F1A),
)

private val PAGE = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 18.sp,
    lineHeight = 28.sp,
)

private val LONG = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val CHIP = DateTimeFormatter.ofPattern("EEE d MMM")
