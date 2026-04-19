package com.example.oop.wearables.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.oop.R
import com.example.oop.ui.theme.AppTheme
import com.example.oop.wearables.WearablesViewModel
import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import com.example.oop.wearables.mvi.WearablesEffect
import com.example.oop.wearables.mvi.WearablesIntent
import com.example.oop.wearables.mvi.WearablesUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun WearablesScreen(
    viewModel: WearablesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WearablesScreen(
        state = state,
        onIntent = viewModel::onIntent,
        effects = viewModel.effects,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WearablesScreen(
    state: WearablesUiState,
    onIntent: (WearablesIntent) -> Unit,
    effects: Flow<WearablesEffect>,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> onIntent(WearablesIntent.PermissionsGranted(granted)) }

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is WearablesEffect.LaunchHealthConnectPermissions ->
                    healthConnectLauncher.launch(effect.permissions)

                is WearablesEffect.ShowError ->
                    snackbarHostState.showSnackbar(effect.message, duration = SnackbarDuration.Short)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.wearables_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.wearables_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onIntent(WearablesIntent.Refresh) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.wearables_refresh),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.missingPermissions.isNotEmpty()) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.wearables_permission_card_title),
                        body = stringResource(R.string.wearables_permission_card_body),
                        ctaLabel = stringResource(R.string.wearables_permission_card_cta),
                        onClick = { onIntent(WearablesIntent.RequestHealthConnectPermissions) },
                    )
                }
            }

            item {
                SectionHeader(text = stringResource(R.string.wearables_sources_header))
            }
            items(
                items = sourceOrder,
                key = { it.name },
            ) { source ->
                val status = state.sources[source] ?: SourceStatus.Uninitialized
                val enabled = source in state.enabledSources
                val canToggle = when (status) {
                    SourceStatus.Unsupported, SourceStatus.NotInstalled -> false
                    else -> true
                }
                SourceRow(
                    source = source,
                    status = status,
                    enabled = enabled,
                    canToggle = canToggle,
                    onToggle = { onIntent(WearablesIntent.SourceToggled(source, it)) },
                )
            }

            item {
                SectionHeader(text = stringResource(R.string.wearables_vitals_header))
            }
            item {
                VitalTile(
                    label = stringResource(R.string.wearables_vital_hr),
                    type = VitalType.HeartRate,
                    sample = state.latest[VitalType.HeartRate],
                    clockTick = state.clockTick,
                )
            }
            item {
                VitalTile(
                    label = stringResource(R.string.wearables_vital_hrv),
                    type = VitalType.HrvRmssd,
                    sample = state.latest[VitalType.HrvRmssd],
                    clockTick = state.clockTick,
                )
            }
            item {
                VitalTile(
                    label = stringResource(R.string.wearables_vital_spo2),
                    type = VitalType.OxygenSaturation,
                    sample = state.latest[VitalType.OxygenSaturation],
                    clockTick = state.clockTick,
                )
            }
            item {
                BloodPressureTile(
                    systolic = state.latest[VitalType.BloodPressureSystolic],
                    diastolic = state.latest[VitalType.BloodPressureDiastolic],
                    clockTick = state.clockTick,
                )
            }
            item {
                VitalTile(
                    label = stringResource(R.string.wearables_vital_body_temp),
                    type = VitalType.BodyTemperature,
                    sample = state.latest[VitalType.BodyTemperature],
                    clockTick = state.clockTick,
                )
            }
            item {
                VitalTile(
                    label = stringResource(R.string.wearables_vital_skin_temp),
                    type = VitalType.SkinTemperature,
                    sample = state.latest[VitalType.SkinTemperature],
                    clockTick = state.clockTick,
                )
            }
            item {
                VitalTile(
                    label = stringResource(R.string.wearables_vital_resp_rate),
                    type = VitalType.RespiratoryRate,
                    sample = state.latest[VitalType.RespiratoryRate],
                    clockTick = state.clockTick,
                )
            }

            item {
                StreamingControls(
                    streaming = state.streaming,
                    onStart = { onIntent(WearablesIntent.StartStreaming) },
                    onStop = { onIntent(WearablesIntent.StopStreaming) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    )
}

private val sourceOrder: List<WearableSource> = listOf(
    WearableSource.HEALTH_CONNECT,
    WearableSource.SAMSUNG_HEALTH,
    WearableSource.MOCK,
)

@Preview(showBackground = true, widthDp = 400, heightDp = 820)
@Composable
private fun WearablesScreenPreview() {
    AppTheme {
        WearablesScreen(
            state = WearablesUiState(
                sources = mapOf(
                    WearableSource.HEALTH_CONNECT to SourceStatus.Ready,
                    WearableSource.SAMSUNG_HEALTH to SourceStatus.Unsupported,
                    WearableSource.MOCK to SourceStatus.Uninitialized,
                ),
                enabledSources = setOf(WearableSource.HEALTH_CONNECT),
                streaming = true,
                missingPermissions = emptySet(),
            ),
            onIntent = {},
            effects = emptyFlow(),
            onBack = {},
        )
    }
}
