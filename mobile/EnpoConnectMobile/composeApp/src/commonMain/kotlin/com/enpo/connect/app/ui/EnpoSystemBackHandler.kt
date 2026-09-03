package com.enpo.connect.app.ui

import androidx.compose.runtime.Composable

@Composable
internal expect fun EnpoSystemBackHandler(enabled: Boolean, onBack: () -> Unit)
