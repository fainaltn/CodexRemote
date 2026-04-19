package app.findeck.mobile.ui.inbox

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.findeck.mobile.R
import app.findeck.mobile.data.model.InboxItem
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.ui.sessions.ShimmerBlock
import app.findeck.mobile.ui.sessions.TimelineNoticeCard
import app.findeck.mobile.ui.sessions.TimelineNoticeTone
import app.findeck.mobile.ui.theme.FindeckSnackbarHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val HOST_ID = "local"

private data class ParsedSharedLink(
    val url: String,
    val companionText: String? = null,
)

private fun parseSharedLinkInput(raw: String): ParsedSharedLink? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val match = Patterns.WEB_URL.matcher(trimmed)
    if (!match.find()) return null
    val extractedUrl = match.group()?.trim()?.trimEnd('。', '！', '!', '，', ',', '；', ';')
        ?: return null
    val companionText = trimmed.replace(extractedUrl, " ").replace(Regex("\\s+"), " ").trim()
    return ParsedSharedLink(
        url = extractedUrl,
        companionText = companionText.ifBlank { null },
    )
}

private fun inboxKindLabel(kind: String, linkLabel: String, fileLabel: String): String = when (kind.lowercase()) {
    "link" -> linkLabel
    "file" -> fileLabel
    else -> kind
}

private fun inboxMetaLabel(
    item: InboxItem,
    statusPrefix: String,
    sourcePrefix: String,
    sizeSuffix: String,
): String = buildString {
    append(statusPrefix)
    append(item.status)
    if (!item.source.isNullOrBlank()) append("$sourcePrefix${item.source}")
    if (item.sizeBytes != null) append(" · ${item.sizeBytes}$sizeSuffix")
}

private fun inboxContractLabel(
    item: InboxItem,
    reviewBundleLabel: String,
    skillRunbookLabel: String,
): String? {
    if (item.contract.isNullOrBlank() && !item.hasReviewBundle && !item.hasSkillRunbook) {
        return null
    }
    return buildString {
        append(item.submissionId ?: item.id)
        if (!item.contract.isNullOrBlank()) append(" · ${item.contract}")
        if (item.hasReviewBundle) append(" · $reviewBundleLabel")
        if (item.hasSkillRunbook) append(" · $skillRunbookLabel")
    }
}

private fun inboxRetryLabel(item: InboxItem, retryPrefix: String, retryTimesSuffix: String): String? {
    val attemptCount = item.retryPolicy?.get("attempt_count")?.toString()
        ?: item.retryAttempts.takeIf { it.isNotEmpty() }?.size?.toString()
    val finalStatus = item.retryPolicy?.get("final_status")?.toString()
    if (attemptCount == null && finalStatus == null) return null
    return buildString {
        append(retryPrefix)
        append(attemptCount ?: "0")
        append(retryTimesSuffix)
        if (!finalStatus.isNullOrBlank()) append(" · $finalStatus")
    }
}

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _submitting = MutableStateFlow(false)
    val submitting = _submitting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    private suspend fun buildClient(serverId: String): Pair<ApiClient, String> {
        val servers = repo.servers.first()
        val server = servers.find { it.id == serverId }
            ?: throw IllegalStateException(
                getApplication<Application>().getString(R.string.inbox_error_server_missing)
            )
        val token = server.token ?: throw IllegalStateException(
            getApplication<Application>().getString(R.string.inbox_error_not_logged_in)
        )
        return ApiClient(server.baseUrl) to token
    }

    fun loadInbox(serverId: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val (client, token) = buildClient(serverId)
                val response = try {
                    client.listInbox(token, HOST_ID)
                } finally {
                    client.close()
                }
                _items.value = response.items
            } catch (e: Exception) {
                _error.value = userFacingMessage(
                    e,
                    getApplication<Application>().getString(R.string.inbox_error_load_failed),
                )
            } finally {
                _loading.value = false
            }
        }
    }

    fun submitLink(
        serverId: String,
        url: String,
        title: String,
        note: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _submitting.value = true
            try {
                val (client, token) = buildClient(serverId)
                val item = try {
                    client.submitInboxLink(
                        token = token,
                        hostId = HOST_ID,
                        url = url,
                        title = title.ifBlank { null },
                        note = note.ifBlank { null },
                        source = "android",
                    )
                } finally {
                    client.close()
                }
                _items.value = listOf(item) + _items.value
                onSuccess()
            } catch (e: Exception) {
                onError(
                    userFacingMessage(
                        e,
                        getApplication<Application>().getString(R.string.inbox_error_submit_failed),
                    )
                )
            } finally {
                _submitting.value = false
            }
        }
    }

    fun uploadFiles(
        serverId: String,
        uris: List<Uri>,
        note: String,
        onSuccess: (count: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _submitting.value = true
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val files = uris.map { uri ->
                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                    val fileName = queryDisplayName(uri)
                        ?: getApplication<Application>().getString(R.string.inbox_upload_fallback_name)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(
                            getApplication<Application>().getString(R.string.inbox_error_file_read_failed)
                        )
                    Triple(fileName, mimeType, bytes)
                }

                val (client, token) = buildClient(serverId)
                val item = try {
                    client.uploadInboxFiles(
                        token = token,
                        hostId = HOST_ID,
                        files = files,
                        note = note.ifBlank { null },
                        source = "android",
                    )
                } finally {
                    client.close()
                }
                _items.value = listOf(item) + _items.value
                onSuccess(files.size)
            } catch (e: Exception) {
                onError(
                    userFacingMessage(
                        e,
                        getApplication<Application>().getString(R.string.inbox_error_upload_failed),
                    )
                )
            } finally {
                _submitting.value = false
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(idx)
                }
            }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    serverId: String,
    onBack: () -> Unit,
    viewModel: InboxViewModel = viewModel(),
) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val submitting by viewModel.submitting.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var linkUrl by remember { mutableStateOf("") }
    var linkTitle by remember { mutableStateOf("") }
    var linkNote by remember { mutableStateOf("") }
    var fileNote by remember { mutableStateOf("") }

    val screenTitle = stringResource(R.string.inbox_screen_title)
    val backDescription = stringResource(R.string.content_desc_back)
    val refreshDescription = stringResource(R.string.content_desc_refresh)
    val inboxLinkLabel = stringResource(R.string.inbox_kind_link)
    val inboxFileLabel = stringResource(R.string.inbox_kind_file)
    val statusPrefix = stringResource(R.string.inbox_meta_status_prefix)
    val sourcePrefix = stringResource(R.string.inbox_meta_source_prefix)
    val sizeSuffix = stringResource(R.string.inbox_meta_size_suffix)
    val reviewBundleLabel = stringResource(R.string.inbox_contract_review_bundle)
    val skillRunbookLabel = stringResource(R.string.inbox_contract_skill_runbook)
    val retryPrefix = stringResource(R.string.inbox_retry_prefix)
    val retryTimesSuffix = stringResource(R.string.inbox_retry_times_suffix)
    val loadingTitle = stringResource(R.string.inbox_loading_title)
    val loadingMessage = stringResource(R.string.inbox_loading_message)
    val loadingFooter = stringResource(R.string.inbox_loading_footer)
    val loadingStateLabel = stringResource(R.string.inbox_loading_state_label)
    val errorTitle = stringResource(R.string.inbox_error_title)
    val errorFooter = stringResource(R.string.inbox_error_footer)
    val errorStateLabel = stringResource(R.string.inbox_error_state_label)
    val retryButtonLabel = stringResource(R.string.inbox_error_retry_button)
    val introText = stringResource(R.string.inbox_intro_text)
    val submitLinkTitle = stringResource(R.string.inbox_submit_link_title)
    val urlLabel = stringResource(R.string.inbox_url_label)
    val linkHintText = stringResource(R.string.inbox_link_hint_text)
    val titleOptionalLabel = stringResource(R.string.inbox_title_optional_label)
    val noteOptionalLabel = stringResource(R.string.inbox_note_optional_label)
    val sendLinkButton = stringResource(R.string.inbox_send_link_button)
    val uploadTitle = stringResource(R.string.inbox_upload_title)
    val uploadNoteLabel = stringResource(R.string.inbox_note_optional_label)
    val pickFilesButton = stringResource(R.string.inbox_pick_files_button)
    val recentDeliveriesTitle = stringResource(R.string.inbox_recent_deliveries_title)
    val emptyTitle = stringResource(R.string.inbox_empty_title)
    val emptyMessage = stringResource(R.string.inbox_empty_message)
    val emptyFooter = stringResource(R.string.inbox_empty_footer)
    val emptyStateLabel = stringResource(R.string.inbox_empty_state_label)
    val linkSuccessMessage = stringResource(R.string.inbox_link_success_message)
    val invalidLinkMessage = stringResource(R.string.inbox_invalid_link_message)

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(
                serverId = serverId,
                uris = uris,
                note = fileNote,
                onSuccess = { count ->
                    fileNote = ""
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.resources.getQuantityString(
                                R.plurals.inbox_upload_success_message,
                                count,
                                count,
                            )
                        )
                    }
                },
                onError = { msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                },
            )
        }
    }
    LaunchedEffect(serverId) {
        viewModel.loadInbox(serverId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadInbox(serverId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = refreshDescription)
                    }
                }
            )
        },
        snackbarHost = { FindeckSnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (error != null) {
                        TimelineNoticeCard(
                            title = errorTitle,
                            message = error ?: "",
                            footer = errorFooter,
                            tone = TimelineNoticeTone.Error,
                            stateLabel = errorStateLabel,
                            content = {
                                Button(
                                    onClick = { viewModel.loadInbox(serverId) },
                                ) {
                                    Text(retryButtonLabel)
                                }
                            },
                        )
                    }

                    Text(
                        text = introText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(submitLinkTitle, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = linkUrl,
                            onValueChange = { linkUrl = it },
                            label = { Text(urlLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = linkHintText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = linkTitle,
                            onValueChange = { linkTitle = it },
                            label = { Text(titleOptionalLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = linkNote,
                            onValueChange = { linkNote = it },
                            label = { Text(noteOptionalLabel) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val parsed = parseSharedLinkInput(linkUrl)
                                if (parsed == null) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(invalidLinkMessage)
                                    }
                                    return@Button
                                }
                                val mergedNote = when {
                                    linkNote.isNotBlank() -> linkNote
                                    !parsed.companionText.isNullOrBlank() -> parsed.companionText
                                    else -> ""
                                }
                                viewModel.submitLink(
                                    serverId = serverId,
                                    url = parsed.url,
                                    title = linkTitle,
                                    note = mergedNote,
                                    onSuccess = {
                                        linkUrl = ""
                                        linkTitle = ""
                                        linkNote = ""
                                        scope.launch { snackbarHostState.showSnackbar(linkSuccessMessage) }
                                    },
                                    onError = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                )
                            },
                            enabled = parseSharedLinkInput(linkUrl) != null && !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (submitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(sendLinkButton)
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(uploadTitle, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = fileNote,
                            onValueChange = { fileNote = it },
                            label = { Text(uploadNoteLabel) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(pickFilesButton)
                        }
                    }
                }
            }

            item {
                Text(recentDeliveriesTitle, style = MaterialTheme.typography.titleMedium)
            }

            if (loading) {
                item {
                    TimelineNoticeCard(
                        title = loadingTitle,
                        message = loadingMessage,
                        footer = loadingFooter,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = loadingStateLabel,
                        content = {
                            ShimmerBlock(lines = 2)
                        },
                    )
                }
            } else if (items.isEmpty()) {
                item {
                    TimelineNoticeCard(
                        title = emptyTitle,
                        message = emptyMessage,
                        footer = emptyFooter,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = emptyStateLabel,
                        content = {
                            Button(
                                onClick = { viewModel.loadInbox(serverId) },
                            ) {
                                Text(refreshDescription)
                            }
                        },
                    )
                }
            } else {
                items(items, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = inboxKindLabel(item.kind, inboxLinkLabel, inboxFileLabel),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = item.createdAt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = item.title ?: item.originalName ?: item.url ?: item.id,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            item.url?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            item.note?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            inboxContractLabel(item, reviewBundleLabel, skillRunbookLabel)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (item.captureSessions.isNotEmpty()) {
                                Text(
                                    text = context.resources.getQuantityString(
                                        R.plurals.inbox_capture_sessions_message,
                                        item.captureSessions.size,
                                        item.captureSessions.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inboxRetryLabel(item, retryPrefix, retryTimesSuffix)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = inboxMetaLabel(item, statusPrefix, sourcePrefix, sizeSuffix),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
