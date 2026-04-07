# Architecture

CodexRemote is a single-user remote control stack for a local Codex CLI host.

## Components

- `apps/server`: Fastify API, SQLite persistence, local file storage, Codex session orchestration
- `apps/web`: Next.js control surface optimized for mobile browsers
- `apps/android`: native Android shell with direct API calls and a WebView bridge for session detail
- `packages/shared`: shared API and schema definitions

## Data ownership

- Session and inbox state live on the backend host
- Web and Android are clients, not sources of truth
- Inbox records are stored in SQLite and corresponding staging directories are stored under `data/submissions/` by default

## Inbox model

Inbox intake is intentionally separate from session artifacts.

Supported flows:

- submit a link
- upload one or more files
- import a submission bundle directory

The backend stores each received item in its own staging directory and exposes recent history to both web and Android.

## Android split

- Native Compose for authentication, server management, inbox, and session list
- WebView for session detail, reusing the existing web screen

This keeps the APK simple while preserving the richer session control UI.
