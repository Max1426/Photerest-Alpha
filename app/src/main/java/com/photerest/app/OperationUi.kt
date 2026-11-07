package com.photerest.app

import android.os.Build
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class OperationStyle(val label: String) {
    EXPRESSIVE("Material 3 Expressive"),
    STANDARD("标准 Material 3")
}

internal val LocalAppAppearance = compositionLocalOf<AppSettings?> { null }
internal val LocalExpressive = compositionLocalOf { false }

private val LabDarkColors = darkColorScheme()
private val LabLightColors = lightColorScheme()

internal fun operationColors(context: Context, settings: AppSettings): ColorScheme = when {
    settings.dynamicColor && Build.VERSION.SDK_INT >= 31 ->
        if (settings.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    settings.dark -> LabDarkColors
    else -> LabLightColors
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OperationTheme(content: @Composable () -> Unit) {
    val settings = LocalAppAppearance.current ?: AppSettings()
    val context = LocalContext.current
    val colors = remember(context, settings.dark, settings.dynamicColor) { operationColors(context, settings) }
    val expressive = settings.operationStyle == OperationStyle.EXPRESSIVE
    CompositionLocalProvider(LocalAppAppearance provides settings, LocalExpressive provides expressive) {
        if (expressive) {
            MaterialExpressiveTheme(
                colorScheme = colors,
                motionScheme = MotionScheme.expressive(),
                content = content
            )
        } else MaterialTheme(colorScheme = colors, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperationScreen(
    title: String,
    backLabel: String,
    back: () -> Unit,
    canDismiss: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = { if (canDismiss) back() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = canDismiss
        )
    ) {
        DialogThemeBars()
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag("operation-page"),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                val expressive = LocalExpressive.current
                TopAppBar(
                    title = {
                        Text(
                            title,
                            modifier = Modifier.semantics { heading() },
                            style = if (expressive) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                            fontWeight = if (expressive) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    navigationIcon = { GlyphButton(Glyph.BACK, backLabel) { if (canDismiss) back() } },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = bottomBar,
            containerColor = MaterialTheme.colorScheme.surface
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
        }
    }
}

@Composable
internal fun OperationGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        OperationHeading(title, Modifier.padding(start = 16.dp, bottom = 8.dp))
        val expressive = LocalExpressive.current
        Surface(
            color = if (expressive) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
            shape = if (expressive) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.extraSmall
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        if (!expressive) HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun OperationHeading(title: String, modifier: Modifier = Modifier) {
    val expressive = LocalExpressive.current
    Text(
        title,
        modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = if (expressive) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        fontWeight = if (expressive) FontWeight.SemiBold else FontWeight.Medium
    )
}

@Composable
internal fun OperationItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = modifier,
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun OperationPageBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperationSheet(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        DialogThemeBars()
        content()
    }
}
