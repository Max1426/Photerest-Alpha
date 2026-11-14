package com.photerest.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

enum class Glyph(val resource: Int) {
    HOME(R.drawable.ic_material_home),
    PLUS(R.drawable.ic_material_add),
    BACK(R.drawable.ic_material_arrow_back),
    SHARE(R.drawable.ic_material_share),
    BOOKMARK(R.drawable.ic_material_bookmark),
    MORE(R.drawable.ic_material_more_horiz),
    CLOSE(R.drawable.ic_material_close),
    FOLDER(R.drawable.ic_material_folder),
    USER(R.drawable.ic_material_person),
    CHECK(R.drawable.ic_material_check),
    TRASH(R.drawable.ic_material_delete),
    EDIT(R.drawable.ic_material_edit),
    INFO(R.drawable.ic_material_info),
    SETTINGS(R.drawable.ic_material_settings),
    IMAGE_UP(R.drawable.ic_material_open_in_new)
}

@Composable
fun Symbol(glyph: Glyph, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Icon(painterResource(glyph.resource), contentDescription = null, modifier = modifier.size(24.dp), tint = color)
}

@Composable
fun GlyphButton(glyph: Glyph, description: String, modifier: Modifier = Modifier, action: () -> Unit) {
    TapIconButton(onClick = action, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = description }) { Symbol(glyph) }
}

@Composable
fun PhotoImage(
    uri: String, modifier: Modifier = Modifier, large: Boolean = false, revision: Long = 0,
    scale: ContentScale = ContentScale.Crop, thumbnail: Bitmap? = null, preferThumbnail: Boolean = false,
    imageModifier: Modifier = Modifier,
    onBitmap: ((Bitmap?) -> Unit)? = null
) {
    val context = LocalContext.current
    var bitmap by remember(uri, large, revision) { mutableStateOf<Bitmap?>(if (large) null else PhotoBitmaps.peek(uri, revision)) }
    LaunchedEffect(uri, large, revision) { if (bitmap == null) bitmap = PhotoBitmaps.load(context, uri, large, revision) }
    // A failed or loading source always keeps the layout stable.
    val preview = if (preferThumbnail) thumbnail ?: bitmap else bitmap ?: thumbnail
    SideEffect { onBitmap?.invoke(preview) }
    Box(modifier.background(if (large) MaterialTheme.colorScheme.background else Panel), contentAlignment = Alignment.Center) {
        if (preview != null) Image(preview.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().then(imageModifier).testTag("bitmap-$uri"), contentScale = scale)
        else Symbol(Glyph.FOLDER, Modifier.size(28.dp), Muted)
    }
}

@Composable
fun PhotoCard(photo: Photo, source: PhotoMotionKey, available: Boolean = true, missingText: String = "原图无法访问", open: () -> Unit, menu: () -> Unit) {
    val motion = LocalPhotoMotion.current
    // Absent preferences fall back to the app default: the text-free grid is on.
    val noText = LocalAppAppearance.current?.noTextMode ?: true
    val tracked = remember(source, photo.added, available) { PhotoCardSource() }
    DisposableEffect(motion, source, tracked) {
        motion?.track(source, tracked)
        onDispose { motion?.untrack(source, tracked) }
    }
    ElevatedCard {
        Box(Modifier.fillMaxWidth().aspectRatio(photo.aspect.coerceIn(0.12f, 6f)).tapClickable(onClick = open).semantics { contentDescription = "查看照片：${photo.name}" }) {
            if (available) PhotoImage(
                photo.uri, Modifier.fillMaxSize()
                    .onGloballyPositioned { tracked.coordinates = it }
                    .drawWithContent { if (motion?.flight?.key != source) drawContent() }
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                revision = photo.added, onBitmap = { tracked.bitmap = it }
            )
            else Column(Modifier.fillMaxSize().background(Panel).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Symbol(Glyph.INFO, color = Muted)
                Spacer(Modifier.height(8.dp)); Text(missingText, fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
            }
        }
        if (!noText) Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(photo.name.substringBeforeLast('.').ifBlank { photo.name }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            GlyphButton(Glyph.MORE, "照片菜单：${photo.name}", action = menu)
        }
    }
}

@Composable
fun Avatar(uri: String?, name: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (uri != null) PhotoImage(uri, Modifier.fillMaxSize())
        else if (!name.isNullOrBlank()) Text(name.take(1).uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        else Symbol(Glyph.USER, Modifier.size(28.dp))
    }
}

@Composable
fun EmptyState(title: String, body: String, button: String? = null, action: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Symbol(Glyph.FOLDER, Modifier.size(42.dp), Muted)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp)); Text(body, color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (button != null) { Spacer(Modifier.height(22.dp)); TapButton(onClick = action) { Text(button) } }
    }
}
