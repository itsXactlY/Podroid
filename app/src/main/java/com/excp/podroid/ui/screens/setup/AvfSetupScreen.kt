/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AvfSetupScreen — the one-time first-run screen that enables hardware-
 * accelerated AVF on supported devices with a SINGLE manual step (enabling the
 * PodroidSetupAssistant accessibility service). After that the screen drives
 * the rest: deep-links to Accessibility Settings, detects enablement, starts
 * the automatic grant (a11y service + bundled adb), and shows live progress.
 *
 * On success it flips EngineSelection to AVF and the VM relaunches fast.
 * On failure it falls back to QEMU/TCG (no engine flip) and offers a manual
 * grant + retry. Idempotent: if perms are already granted it short-circuits.
 */
package com.excp.podroid.avfpair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.theme.PodroidTokens

@Composable
fun AvfSetupScreen(
    windowSizeClass: androidx.compose.material3.windowsizeclass.WindowSizeClass,
    onDone: () -> Unit,
    viewModel: AvfSetupViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check service-enabled when the user returns from Accessibility Settings.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
            }
        )
    }

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PodroidTokens.Spacing.XL),
        ) {
            Spacer(Modifier.height(PodroidTokens.Spacing.XL))
            PodroidSectionLabel(stringResource(R.string.avf_speedup_title))
            Text(
                text = stringResource(R.string.avf_speedup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PodroidTokens.Spacing.MD))
            Text(
                text = stringResource(R.string.avf_speedup_benefit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(PodroidTokens.Spacing.LG))

            when {
                ui.alreadyGranted -> {
                    SuccessCard(stringResource(R.string.avf_already_done))
                    Spacer(Modifier.height(PodroidTokens.Spacing.LG))
                    PodroidPrimaryButton(stringResource(R.string.continue_label), onClick = onDone)
                }
                ui.finished -> {
                    if (ui.success) {
                        SuccessCard(stringResource(R.string.avf_success_body))
                    } else {
                        FailureCard(
                            body = stringResource(R.string.avf_failure_body),
                            manual = stringResource(R.string.avf_grant_commands2),
                        )
                    }
                    Spacer(Modifier.height(PodroidTokens.Spacing.LG))
                    if (!ui.success) {
                        PodroidPrimaryButton(stringResource(R.string.avf_action_retry), onClick = viewModel::start)
                        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    }
                    PodroidGhostButton(stringResource(R.string.avf_skip), onClick = {
                        viewModel.skip(); onDone()
                    })
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    PodroidGhostButton(stringResource(R.string.continue_label), onClick = onDone)
                }
                else -> {
                    // Step 1: enable accessibility service.
                    PodroidSectionLabel(stringResource(R.string.avf_step_enable_service))
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    Text(
                        text = if (ui.serviceEnabled)
                            stringResource(R.string.avf_service_enabled)
                        else
                            stringResource(R.string.avf_service_not_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ui.serviceEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    PodroidGhostButton(
                        text = stringResource(R.string.avf_open_accessibility),
                        onClick = viewModel::openAccessibility,
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.LG))

                    // Step 2: run.
                    PodroidSectionLabel(stringResource(R.string.avf_step_run))
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    val running = ui.phase != "idle" && !ui.finished
                    PodroidPrimaryButton(
                        text = stringResource(R.string.avf_start_setup),
                        onClick = viewModel::start,
                        enabled = ui.serviceEnabled && !running,
                    )
                    if (running) {
                        Spacer(Modifier.height(PodroidTokens.Spacing.MD))
                        val label = when (ui.phase) {
                            "enabling" -> stringResource(R.string.avf_progress_enabling)
                            "opening_pair" -> stringResource(R.string.avf_progress_opening_pair)
                            "awaiting_adb" -> stringResource(R.string.avf_progress_awaiting_adb)
                            "granting" -> stringResource(R.string.avf_progress_granting)
                            "reverting" -> stringResource(R.string.avf_progress_reverting)
                            else -> ui.phase
                        }
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        if (ui.detail.isNotEmpty()) {
                            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                            Text(
                                ui.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Spacer(Modifier.height(PodroidTokens.Spacing.LG))
                    PodroidGhostButton(stringResource(R.string.avf_skip), onClick = {
                        viewModel.skip(); onDone()
                    })
                }
            }
            Spacer(Modifier.height(PodroidTokens.Spacing.XL))
        }
    }
}

@Composable
private fun SuccessCard(body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PodroidTokens.Spacing.MD),
    ) {
        Text(
            stringResource(R.string.avf_success_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailureCard(body: String, manual: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PodroidTokens.Spacing.MD),
    ) {
        Text(
            stringResource(R.string.avf_failure_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(PodroidTokens.Spacing.MD))
        Text(
            stringResource(R.string.avf_manual_grant_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.XS))
        Text(
            manual,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
