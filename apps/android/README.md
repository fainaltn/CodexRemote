# apps/android

中文：CodexRemote 的原生 Android 客户端。  
English: Native Android client for CodexRemote.

## Stack / 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Ktor
- DataStore

## Current Screens / 当前页面

中文：
- Splash
- 服务器列表与添加服务器
- 登录
- 会话列表
- 会话详情
- Inbox 投递与最近记录

English:
- Splash
- Server list and add-server flow
- Login
- Session list
- Session detail
- Inbox submit flow and recent history

## Build / 构建

```bash
cd apps/android
cp local.properties.example local.properties
./gradlew assembleDebug
```

中文：Debug APK 默认输出到 `app/build/outputs/apk/debug/app-debug.apk`。  
English: The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

中文：如果本机已经配置 `ANDROID_HOME`，也可以不创建 `local.properties`。  
English: If `ANDROID_HOME` is already configured, you can skip `local.properties`.

## Server Topology / 服务端连接

中文：
- `baseUrl`：Fastify API 地址
- `webUrl`：可选的 Web UI 地址，供会话详情 WebView 使用

English:
- `baseUrl`: Fastify API origin
- `webUrl`: optional web UI origin used by the session-detail WebView

## Inbox Behavior / Inbox 行为

中文：Android 端不维护独立的最近投递数据库，Inbox 页面读取的是后端 staging 存储。  
English: The Android app does not keep a separate local inbox-history database; the inbox screen reads recent items from backend staging storage.

中文：如果历史记录要清理，应优先清理后端数据，除非你未来要新增 app 内删除入口。  
English: Historical inbox records should be cleaned on the backend unless you later add an in-app delete action.
