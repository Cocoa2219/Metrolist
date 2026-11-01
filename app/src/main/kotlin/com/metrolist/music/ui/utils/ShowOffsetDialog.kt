package com.metrolist.music.ui.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val MAX_OFFSET_MS = 15_000
private const val STEP_MS = 50

@Composable
fun ShowOffsetDialog(songProvider: () -> SongEntity?) {
    val database = LocalDatabase.current
    val focusManager = LocalFocusManager.current
    val song = songProvider()
    var lyricsOffset by rememberSaveable { mutableIntStateOf(song?.lyricsOffset ?: 0) }
    var textFieldValue by rememberSaveable { mutableStateOf(lyricsOffset.toString()) }

    fun setOffset(value: Int) {
        lyricsOffset = value.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        textFieldValue = lyricsOffset.toString()
    }

    LaunchedEffect(song?.id) {
        song?.let { setOffset(it.lyricsOffset) }
    }

    // Restarted on every change, so the delay debounces DB writes while dragging the slider.
    LaunchedEffect(lyricsOffset) {
        delay(300)
        songProvider()?.takeIf { it.lyricsOffset != lyricsOffset }?.let {
            database.query { upsert(it.copy(lyricsOffset = lyricsOffset)) }
        }
    }

    fun commitTextFieldValue() = setOffset(textFieldValue.toIntOrNull() ?: 0)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.fast_forward),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.lyrics_offset),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = textFieldValue,
                onValueChange = { newText ->
                    val negative = newText.startsWith('-')
                    val digits = newText.filter { it.isDigit() }.take(MAX_OFFSET_MS.toString().length)
                    textFieldValue = if (negative) "-$digits" else digits
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.displaySmall.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                ),
                suffix = {
                    Text(
                        text = "ms",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 240.dp)
                    .onFocusChanged { if (!it.isFocused) commitTextFieldValue() },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitTextFieldValue()
                        focusManager.clearFocus()
                    }
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalIconButton(
                onClick = { setOffset(lyricsOffset - STEP_MS) },
                enabled = lyricsOffset > -MAX_OFFSET_MS
            ) {
                Icon(
                    painter = painterResource(R.drawable.remove),
                    contentDescription = "-${STEP_MS}ms"
                )
            }

            Spacer(Modifier.width(8.dp))

            Slider(
                value = lyricsOffset.toFloat(),
                onValueChange = { setOffset((it / STEP_MS).roundToInt() * STEP_MS) },
                valueRange = -MAX_OFFSET_MS.toFloat()..MAX_OFFSET_MS.toFloat(),
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(8.dp))

            FilledTonalIconButton(
                onClick = { setOffset(lyricsOffset + STEP_MS) },
                enabled = lyricsOffset < MAX_OFFSET_MS
            ) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = "+${STEP_MS}ms"
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp)
        ) {
            Text(
                text = "-${MAX_OFFSET_MS}ms",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "+${MAX_OFFSET_MS}ms",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { setOffset(0) },
            enabled = lyricsOffset != 0
        ) {
            Icon(
                painter = painterResource(R.drawable.replay),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.reset))
        }
    }
}
