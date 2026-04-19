package app.findeck.mobile.ui.sessions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.findeck.mobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val SESSION_LINKS_CACHE_DIR = "session-links"
internal const val SESSION_LINK_ANNOTATION_TAG = "session-file-link"
internal val LocalSessionFileLinkHandler =
    compositionLocalOf<((SessionFileLinkRequest) -> Unit)?> { null }

internal data class SessionFileLinkRequest(
    val target: String,
    val displayLabel: String? = null,
)

internal fun isLikelyHostAbsolutePath(target: String): Boolean {
    val trimmed = target.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return false
    if (trimmed.startsWith("file://") || trimmed.startsWith("content://")) return false
    return trimmed.startsWith("/") || trimmed.matches(Regex("^[A-Za-z]:[\\\\/].+"))
}

internal sealed interface SessionFileLinkResolution {
    val target: String
    val displayLabel: String

    data class CachedFile(
        override val target: String,
        override val displayLabel: String,
        val file: File,
        val mimeType: String,
    ) : SessionFileLinkResolution

    data class WebLink(
        override val target: String,
        override val displayLabel: String,
    ) : SessionFileLinkResolution

    data class Unresolved(
        override val target: String,
        override val displayLabel: String,
        val reason: String,
    ) : SessionFileLinkResolution
}

internal suspend fun resolveSessionFileLink(
    context: Context,
    request: SessionFileLinkRequest,
): SessionFileLinkResolution = withContext(Dispatchers.IO) {
    val target = request.target.trim()
    val displayLabel = request.displayLabel?.trim().takeIf { !it.isNullOrBlank() }
        ?: inferDisplayLabel(target)

    if (target.isBlank()) {
        return@withContext SessionFileLinkResolution.Unresolved(
            target = target,
            displayLabel = displayLabel,
            reason = context.getString(R.string.session_detail_file_link_unavailable),
        )
    }

    val uri = runCatching { Uri.parse(target) }.getOrNull()
    val scheme = uri?.scheme?.lowercase().orEmpty()

    return@withContext when {
        scheme == "content" && uri != null -> copyContentUriToCache(context, uri, target, displayLabel)
        scheme == "file" && uri != null -> copyLocalFileUriToCache(context, uri, target, displayLabel)
        scheme == "http" || scheme == "https" -> {
            if (shouldDownloadHttpLink(target, displayLabel)) {
                downloadHttpLinkToCache(context, target, displayLabel)
            } else {
                SessionFileLinkResolution.WebLink(
                    target = target,
                    displayLabel = displayLabel,
                )
            }
        }
        target.startsWith("/") || target.matches(Regex("^[A-Za-z]:[\\\\/].+")) -> {
            copyAbsoluteFileToCache(context, File(target), target, displayLabel)
        }
        else -> {
            SessionFileLinkResolution.Unresolved(
                target = target,
                displayLabel = displayLabel,
                reason = context.getString(R.string.session_detail_file_link_unavailable),
            )
        }
    }
}

internal fun openSessionFileLink(context: Context, resolution: SessionFileLinkResolution): Boolean {
    return when (resolution) {
        is SessionFileLinkResolution.CachedFile -> {
            val uri = cachedFileUri(context, resolution.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolution.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchChooser(context, intent, context.getString(R.string.session_detail_file_link_open))
        }
        is SessionFileLinkResolution.WebLink -> {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolution.target)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchChooser(context, intent, context.getString(R.string.session_detail_file_link_open))
        }
        is SessionFileLinkResolution.Unresolved -> {
            val target = resolution.target.trim()
            if (target.startsWith("http://") || target.startsWith("https://")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchChooser(context, intent, context.getString(R.string.session_detail_file_link_open))
            } else {
                false
            }
        }
    }
}

internal fun shareSessionFileLink(context: Context, resolution: SessionFileLinkResolution): Boolean {
    return when (resolution) {
        is SessionFileLinkResolution.CachedFile -> {
            val uri = cachedFileUri(context, resolution.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = resolution.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchChooser(context, intent, context.getString(R.string.session_detail_file_link_share))
        }
        is SessionFileLinkResolution.WebLink -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, resolution.target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchChooser(context, intent, context.getString(R.string.session_detail_file_link_share))
        }
        is SessionFileLinkResolution.Unresolved -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, resolution.target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchChooser(context, intent, context.getString(R.string.session_detail_file_link_share))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionFileLinkActionSheet(
    resolution: SessionFileLinkResolution,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val title = when (resolution) {
        is SessionFileLinkResolution.CachedFile -> stringResource(R.string.session_detail_file_link_title_saved)
        is SessionFileLinkResolution.WebLink -> stringResource(R.string.session_detail_file_link_title_web)
        is SessionFileLinkResolution.Unresolved -> stringResource(R.string.session_detail_file_link_title_unavailable)
    }
    val summary = when (resolution) {
        is SessionFileLinkResolution.CachedFile ->
            stringResource(R.string.session_detail_file_link_cached_summary, resolution.displayLabel)
        is SessionFileLinkResolution.WebLink ->
            stringResource(R.string.session_detail_file_link_web_summary, resolution.displayLabel)
        is SessionFileLinkResolution.Unresolved ->
            resolution.reason
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (resolution is SessionFileLinkResolution.Unresolved && !resolution.target.startsWith("http")) {
                FileLinkActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = stringResource(R.string.session_detail_file_link_copy),
                    onClick = onCopy,
                )
            } else {
                FileLinkActionRow(
                    icon = Icons.Filled.OpenInNew,
                    label = stringResource(R.string.session_detail_file_link_open),
                    onClick = onOpen,
                )
                FileLinkActionRow(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.session_detail_file_link_share),
                    onClick = onShare,
                )
                FileLinkActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = stringResource(R.string.session_detail_file_link_copy),
                    onClick = onCopy,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FileLinkActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun launchChooser(context: Context, intent: Intent, title: String): Boolean {
    return runCatching {
        val chooser = Intent.createChooser(intent, title).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
        true
    }.getOrElse { false }
}

private fun cachedFileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

private fun cacheDirectory(context: Context): File =
    File(context.cacheDir, SESSION_LINKS_CACHE_DIR).apply { mkdirs() }

private fun createCacheFile(context: Context, suggestedName: String): File {
    val sanitized = sanitizeFileName(suggestedName).ifBlank { "session-link" }
    val name = uniqueCacheFileName(sanitized)
    return File(cacheDirectory(context), name)
}

private fun uniqueCacheFileName(name: String): String {
    val dotIndex = name.lastIndexOf('.')
    val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
    val extension = if (dotIndex > 0) name.substring(dotIndex) else ""
    val hash = name.hashCode().toUInt().toString(16)
    return if (extension.isNotBlank()) {
        "$base-$hash$extension"
    } else {
        "$base-$hash"
    }
}

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun inferDisplayLabel(target: String): String {
    val uri = runCatching { Uri.parse(target) }.getOrNull()
    val lastSegment = uri?.lastPathSegment?.trim().orEmpty()
    return when {
        lastSegment.isNotBlank() -> lastSegment
        target.contains('/') -> target.substringAfterLast('/').ifBlank { target }
        else -> target.ifBlank { "session-link" }
    }
}

private fun shouldDownloadHttpLink(target: String, displayLabel: String): Boolean {
    val candidates = listOf(displayLabel, Uri.parse(target).lastPathSegment.orEmpty())
    return candidates.any { looksLikeFileName(it) }
}

private fun looksLikeFileName(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return false
    val lastSegment = trimmed.substringAfterLast('/')
    if (!lastSegment.contains('.')) return false
    val extension = lastSegment.substringAfterLast('.')
    return extension.length in 1..12
}

private fun copyLocalFileUriToCache(
    context: Context,
    uri: Uri,
    target: String,
    displayLabel: String,
): SessionFileLinkResolution {
    val path = uri.path?.takeIf { it.isNotBlank() }
        ?: return SessionFileLinkResolution.Unresolved(
            target = target,
            displayLabel = displayLabel,
            reason = context.getString(R.string.session_detail_file_link_unavailable),
        )
    val file = File(path)
    return copyFileToCache(context, file, target, displayLabel)
}

private fun copyAbsoluteFileToCache(
    context: Context,
    file: File,
    target: String,
    displayLabel: String,
): SessionFileLinkResolution {
    return copyFileToCache(context, file, target, displayLabel)
}

private fun copyFileToCache(
    context: Context,
    source: File,
    target: String,
    displayLabel: String,
): SessionFileLinkResolution {
    if (!source.exists() || !source.isFile) {
        return SessionFileLinkResolution.Unresolved(
            target = target,
            displayLabel = displayLabel,
            reason = context.getString(R.string.session_detail_file_link_unavailable),
        )
    }

    val cacheFile = createCacheFile(context, displayLabel.ifBlank { source.name })
    source.inputStream().use { input ->
        FileOutputStream(cacheFile).use { output ->
            input.copyTo(output)
        }
    }
    return SessionFileLinkResolution.CachedFile(
        target = target,
        displayLabel = displayLabel,
        file = cacheFile,
        mimeType = guessMimeType(cacheFile.name),
    )
}

private fun copyContentUriToCache(
    context: Context,
    uri: Uri,
    target: String,
    displayLabel: String,
): SessionFileLinkResolution {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)
    val name = displayLabel.ifBlank { inferDisplayLabel(target) }
    val cacheFile = createCacheFile(context, name)

    val input = resolver.openInputStream(uri)
        ?: return SessionFileLinkResolution.Unresolved(
            target = target,
            displayLabel = displayLabel,
            reason = context.getString(R.string.session_detail_file_link_unavailable),
        )

    input.use { source ->
        FileOutputStream(cacheFile).use { output ->
            source.copyTo(output)
        }
    }

    return SessionFileLinkResolution.CachedFile(
        target = target,
        displayLabel = displayLabel,
        file = cacheFile,
        mimeType = mimeType ?: guessMimeType(cacheFile.name),
    )
}

private fun downloadHttpLinkToCache(
    context: Context,
    target: String,
    displayLabel: String,
): SessionFileLinkResolution {
    val uri = Uri.parse(target)
    val fileName = displayLabel.ifBlank {
        uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "session-link"
    }
    val cacheFile = createCacheFile(context, fileName)
    val connection = (URL(target).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        instanceFollowRedirects = true
    }

    return try {
        connection.connect()
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            SessionFileLinkResolution.WebLink(
                target = target,
                displayLabel = displayLabel,
            )
        } else {
            connection.inputStream.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            SessionFileLinkResolution.CachedFile(
                target = target,
                displayLabel = displayLabel,
                file = cacheFile,
                mimeType = connection.contentType ?: guessMimeType(cacheFile.name),
            )
        }
    } catch (_: IOException) {
        SessionFileLinkResolution.WebLink(
            target = target,
            displayLabel = displayLabel,
        )
    } finally {
        connection.disconnect()
    }
}

private fun guessMimeType(fileName: String): String {
    val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
        .ifBlank { fileName.substringAfterLast('.', "").lowercase() }
    return if (extension.isBlank()) {
        "application/octet-stream"
    } else {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
    }
}
