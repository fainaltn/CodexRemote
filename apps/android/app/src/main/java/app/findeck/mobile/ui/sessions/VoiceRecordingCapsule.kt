package app.findeck.mobile.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.findeck.mobile.R

@Composable
internal fun VoiceRecordingCapsule(
    visible: Boolean,
    uiState: SessionVoiceUiState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )

                VoiceLevelBars(
                    levels = uiState.levels,
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp),
                )

                Text(
                    text = formatVoiceElapsed(uiState.elapsedMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val actionLabel = if (uiState.recording) {
                    stringResource(R.string.voice_capsule_stop)
                } else {
                    stringResource(R.string.voice_capsule_transcribing)
                }
                Text(
                    text = actionLabel,
                    modifier = Modifier.clickable(enabled = uiState.recording, onClick = onStop),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (uiState.recording) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Text(
                    text = stringResource(R.string.voice_capsule_cancel),
                    modifier = Modifier.clickable(onClick = onCancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoiceLevelBars(
    levels: List<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val samples = if (levels.isEmpty()) List(24) { 0.08f } else levels.takeLast(24)
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (samples.size - 1)) / samples.size).coerceAtLeast(2f)
        samples.forEachIndexed { index, sample ->
            val height = (size.height * sample.coerceIn(0.12f, 1f)).coerceAtLeast(4.dp.toPx())
            val left = index * (barWidth + gap)
            val top = (size.height - height) / 2f
            drawRoundRect(
                color = Color(0xFF5A7BF2),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private fun formatVoiceElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
