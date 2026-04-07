package dev.codexremote.android.ui.inbox

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.data.model.InboxItem
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.repository.ServerRepository
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

private fun inboxKindLabel(kind: String): String = when (kind.lowercase()) {
    "link" -> "链接"
    "file" -> "文件"
    else -> kind
}

private fun inboxMetaLabel(item: InboxItem): String = buildString {
    append("状态：${item.status}")
    if (!item.source.isNullOrBlank()) append(" · 来源：${item.source}")
    if (item.sizeBytes != null) append(" · ${item.sizeBytes} 字节")
}

private fun inboxContractLabel(item: InboxItem): String? {
    if (item.contract.isNullOrBlank() && !item.hasReviewBundle && !item.hasSkillRunbook) {
        return null
    }
    return buildString {
        append(item.submissionId ?: item.id)
        if (!item.contract.isNullOrBlank()) append(" · ${item.contract}")
        if (item.hasReviewBundle) append(" · review bundle")
        if (item.hasSkillRunbook) append(" · skill-runbook")
    }
}

private fun inboxRetryLabel(item: InboxItem): String? {
    val attemptCount = item.retryPolicy?.get("attempt_count")?.toString()
        ?: item.retryAttempts.takeIf { it.isNotEmpty() }?.size?.toString()
    val finalStatus = item.retryPolicy?.get("final_status")?.toString()
    if (attemptCount == null && finalStatus == null) return null
    return buildString {
        append("重试：${attemptCount ?: "0"} 次")
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

    private suspend fun buildClient(serverId: String): Pair<ApiClient, String> {
        val servers = repo.servers.first()
        val server = servers.find { it.id == serverId }
            ?: throw IllegalStateException("服务器不存在")
        val token = server.token ?: throw IllegalStateException("尚未登录")
        return ApiClient(server.baseUrl) to token
    }

    fun loadInbox(serverId: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val (client, token) = buildClient(serverId)
                val response = client.listInbox(token, HOST_ID)
                client.close()
                _items.value = response.items
            } catch (e: Exception) {
                _error.value = e.message ?: "加载收件箱失败"
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
                val item = client.submitInboxLink(
                    token = token,
                    hostId = HOST_ID,
                    url = url,
                    title = title.ifBlank { null },
                    note = note.ifBlank { null },
                    source = "android",
                )
                client.close()
                _items.value = listOf(item) + _items.value
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "提交链接失败")
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
                    val fileName = queryDisplayName(uri) ?: "收件箱上传"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取文件")
                    Triple(fileName, mimeType, bytes)
                }

                val (client, token) = buildClient(serverId)
                val item = client.uploadInboxFiles(
                    token = token,
                    hostId = HOST_ID,
                    files = files,
                    note = note.ifBlank { null },
                    source = "android",
                )
                client.close()
                _items.value = listOf(item) + _items.value
                onSuccess(files.size)
            } catch (e: Exception) {
                onError(e.message ?: "上传文件失败")
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
                            if (count == 1) "文件已投递到收件箱" else "已投递 $count 个文件到收件箱"
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
                title = { Text("收件箱") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadInbox(serverId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Text(
                        text = "把链接或文件投递到主机的收件箱。这里是后续知识系统整理前的轻量待收集区。",
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
                        Text("提交链接", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = linkUrl,
                            onValueChange = { linkUrl = it },
                            label = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "可直接粘贴包含标题或短语的分享文本，应用会自动提取其中的链接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = linkTitle,
                            onValueChange = { linkTitle = it },
                            label = { Text("标题（可选）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = linkNote,
                            onValueChange = { linkNote = it },
                            label = { Text("备注（可选）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val parsed = parseSharedLinkInput(linkUrl)
                                if (parsed == null) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("未识别到有效链接，请粘贴 URL 或包含 URL 的分享文本")
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
                                        scope.launch { snackbarHostState.showSnackbar("链接已投递到收件箱") }
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
                                Text("发送链接")
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
                        Text("上传文件", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = fileNote,
                            onValueChange = { fileNote = it },
                            label = { Text("备注（可选）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("选择文件（可多选）")
                        }
                    }
                }
            }

            item {
                Text("最近投递", style = MaterialTheme.typography.titleMedium)
            }

            if (loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (items.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Inbox,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "收件箱还是空的",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                                    text = inboxKindLabel(item.kind),
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
                            inboxContractLabel(item)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (item.captureSessions.isNotEmpty()) {
                                Text(
                                    text = "capture sessions: ${item.captureSessions.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inboxRetryLabel(item)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = inboxMetaLabel(item),
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
