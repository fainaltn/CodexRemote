package app.findeck.mobile.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.findeck.mobile.R
import app.findeck.mobile.StartupUiState
import app.findeck.mobile.ui.sessions.TimelineNoticeCard
import app.findeck.mobile.ui.sessions.TimelineNoticeTone

@Composable
fun SplashScreen(
    startupState: StartupUiState,
    onOpenServers: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    val title = when (startupState) {
        StartupUiState.Loading -> stringResource(R.string.splash_loading_title)
        StartupUiState.NoTrustedServer -> stringResource(R.string.splash_waiting_pairing_title)
        is StartupUiState.Reconnecting -> stringResource(R.string.splash_reconnecting_title)
        is StartupUiState.Reconnected -> stringResource(R.string.splash_reconnected_title)
        is StartupUiState.ReconnectFailed -> stringResource(R.string.splash_reconnect_failed_title)
    }
    val message = when (startupState) {
        StartupUiState.Loading -> stringResource(R.string.splash_loading_message)
        StartupUiState.NoTrustedServer -> stringResource(R.string.splash_waiting_pairing_message)
        is StartupUiState.Reconnecting -> stringResource(
            R.string.splash_reconnecting_message,
            startupState.serverLabel,
        )
        is StartupUiState.Reconnected -> stringResource(
            R.string.splash_reconnected_message,
            startupState.serverLabel,
        )
        is StartupUiState.ReconnectFailed -> startupState.message
    }
    val footer = when (startupState) {
        StartupUiState.Loading -> stringResource(R.string.splash_bootstrap_note)
        StartupUiState.NoTrustedServer -> stringResource(R.string.splash_waiting_pairing_footer)
        is StartupUiState.Reconnecting -> stringResource(R.string.splash_reconnecting_footer)
        is StartupUiState.Reconnected -> stringResource(R.string.splash_reconnected_footer)
        is StartupUiState.ReconnectFailed -> stringResource(R.string.splash_reconnect_failed_footer)
    }
    val stateLabel = when (startupState) {
        StartupUiState.Loading -> stringResource(R.string.splash_state_loading)
        StartupUiState.NoTrustedServer -> stringResource(R.string.splash_state_waiting_pairing)
        is StartupUiState.Reconnecting -> stringResource(R.string.splash_state_reconnecting)
        is StartupUiState.Reconnected -> stringResource(R.string.splash_state_reconnected)
        is StartupUiState.ReconnectFailed -> stringResource(R.string.splash_state_reconnect_failed)
    }
    val tone = when (startupState) {
        StartupUiState.Loading,
        is StartupUiState.Reconnecting,
        is StartupUiState.Reconnected -> TimelineNoticeTone.Neutral
        StartupUiState.NoTrustedServer -> TimelineNoticeTone.Warning
        is StartupUiState.ReconnectFailed -> TimelineNoticeTone.Error
    }
    val showSpinner = startupState is StartupUiState.Loading || startupState is StartupUiState.Reconnecting
    val targetServerLabel = when (startupState) {
        is StartupUiState.Reconnecting -> startupState.serverLabel
        is StartupUiState.Reconnected -> startupState.serverLabel
        is StartupUiState.ReconnectFailed -> startupState.serverLabel
        StartupUiState.Loading,
        StartupUiState.NoTrustedServer -> null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                    .align(Alignment.TopCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                ) {}
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                                center = Offset(540f, 120f),
                                radius = 620f,
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 10.dp,
                    shadowElevation = 20.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(92.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground_raster),
                                    contentDescription = stringResource(R.string.app_name),
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(18.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.console_brand_label),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }

                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.splash_tagline),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiniPill(text = stateLabel)
                        }

                        TimelineNoticeCard(
                            title = title,
                            message = message,
                            footer = footer,
                            tone = tone,
                            stateLabel = targetServerLabel?.takeIf { it.isNotBlank() },
                            content = {
                                if (showSpinner) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = stringResource(R.string.splash_loading_detail),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = onOpenServers,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.splash_open_servers_button))
                            }
                            TextButton(onClick = onOpenPairing) {
                                Text(stringResource(R.string.splash_open_pairing_button))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
