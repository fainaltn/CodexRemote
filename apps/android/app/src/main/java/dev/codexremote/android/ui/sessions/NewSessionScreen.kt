package dev.codexremote.android.ui.sessions

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.data.model.BrowseProjectsResponse
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.data.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val HOST_ID = "local"

class NewSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _browser = MutableStateFlow<BrowseProjectsResponse?>(null)
    val browser = _browser.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    suspend fun load(serverId: String, path: String? = null) {
        _loading.value = true
        _error.value = null
        try {
            val servers = repo.servers.first()
            val server = servers.find { it.id == serverId }
                ?: throw IllegalStateException("服务器不存在")
            val token = server.token ?: throw IllegalStateException("尚未登录")
            val client = ApiClient(server.baseUrl)
            try {
                _browser.value = client.browseProjects(token, HOST_ID, path)
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            _error.value = userFacingMessage(e, "读取目录失败")
        } finally {
            _loading.value = false
        }
    }

    fun openPath(serverId: String, path: String? = null) {
        viewModelScope.launch {
            load(serverId, path)
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    serverId: String,
    initialCwd: String?,
    onProjectSelected: (cwd: String) -> Unit,
    onBack: () -> Unit,
    viewModel: NewSessionViewModel = viewModel(),
) {
    val browser by viewModel.browser.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(serverId, initialCwd) {
        viewModel.load(serverId, initialCwd)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建线程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (browser?.parentPath != null) {
                        IconButton(onClick = { viewModel.openPath(serverId, browser?.parentPath) }) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "上一级")
                        }
                    }
                    IconButton(
                        onClick = {
                            val cwd = browser?.currentPath ?: return@IconButton
                            onProjectSelected(cwd)
                        },
                        enabled = !loading && !browser?.currentPath.isNullOrBlank(),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "将当前目录设为工作区")
                    }
                    IconButton(onClick = { viewModel.openPath(serverId, browser?.currentPath ?: initialCwd) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "当前目录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = browser?.currentPath ?: "正在读取…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                loading && browser == null -> {
                    TimelineNoticeCard(
                        title = "正在读取目录",
                        message = "正在扫描当前主机上的项目文件夹。",
                        footer = "这通常只需要几秒钟。",
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = "加载中",
                        content = {
                            ShimmerBlock(lines = 2)
                        },
                    )
                }

                !loading && browser?.entries.isNullOrEmpty() -> {
                    TimelineNoticeCard(
                        title = "当前目录没有可选项目",
                        message = "这个位置下还没有项目文件夹，或者目录暂时无法列出。",
                        footer = "你可以返回上一级，或刷新后再看一次。",
                        tone = TimelineNoticeTone.Warning,
                        stateLabel = "空目录",
                        content = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.openPath(serverId, browser?.parentPath ?: initialCwd) },
                                    enabled = browser?.parentPath != null || initialCwd != null,
                                ) {
                                    Text("返回上一级")
                                }
                                Button(
                                    onClick = { viewModel.openPath(serverId, browser?.currentPath ?: initialCwd) },
                                    enabled = !loading,
                                ) {
                                    Text("刷新")
                                }
                            }
                        },
                    )
                }

                else -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(browser?.entries ?: emptyList(), key = { it.path }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.openPath(serverId, entry.path)
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("›")
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "这里只负责挑选当前主机上的项目目录。确定目录后，会先把项目区块加入会话列表，再从项目详情页发送首条消息来创建会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (error != null) {
                TimelineNoticeCard(
                    title = "目录读取失败",
                    message = error ?: "",
                    footer = "确认主机在线后，再试一次通常就能恢复。",
                    tone = TimelineNoticeTone.Error,
                    stateLabel = "错误",
                    content = {
                        Button(
                            onClick = { viewModel.openPath(serverId, browser?.currentPath ?: initialCwd) },
                        ) {
                            Text("重试")
                        }
                    },
                )
            }

            Button(
                onClick = {
                    val cwd = browser?.currentPath ?: return@Button
                    onProjectSelected(cwd)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && !browser?.currentPath.isNullOrBlank(),
            ) {
                Text(if (loading) "处理中…" else "将当前目录加入会话列表")
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
