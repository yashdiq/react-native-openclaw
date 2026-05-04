# react-native-openclaw

Run [OpenClaw](https://github.com/openclaw/openclaw) AI agent gateway headlessly on Android — no Termux, no root, no proot. Any React Native app can install this package and start an OpenClaw gateway process on-device.

## How It Works

The package bundles a glibc dynamic loader and shared libraries. At first run, it downloads Node.js and installs OpenClaw via npm. The gateway runs as an Android foreground service.

```
APK ships:     libld_linux.so (JNI) + 7 glibc libs (assets)
First run:     Downloads Node.js → npm install openclaw
Runtime:       ld-linux → node.bin → openclaw gateway (foreground service)
```

## Installation

```bash
yarn add react-native-openclaw
# or
npm install react-native-openclaw
```

That's it. Autolinking handles the native module registration, AndroidManifest merging, and asset packaging.

### Requirements

- React Native 0.73+ (bare workflow)
- Android device/emulator (arm64)
- ~40MB download on first run (Node.js)
- Android 7.0+ (API 24)

## Usage

### Setup (First Run)

Downloads Node.js and installs OpenClaw. Call once, typically in an onboarding screen.

```ts
import { openclaw, onSetupProgress } from 'react-native-openclaw'

// Listen to setup progress
const unsub = onSetupProgress(({ step, percent }) => {
  console.log(`${step} (${percent}%)`)
})

await openclaw.runSetup()
unsub.remove()
```

### Start Gateway

```ts
import { openclaw, onLog, onGatewayExit } from 'react-native-openclaw'

// Stream logs
const logUnsub = onLog((line) => console.log(line))

// Handle unexpected exits
const exitUnsub = onGatewayExit((code) => {
  console.log(`Gateway exited with code ${code}`)
})

// Start with environment variables
await openclaw.startGateway({
  envVars: {
    ZAI_API_KEY: 'your-api-key',
    TELEGRAM_BOT_TOKEN: 'your-bot-token',
  },
})
```

### Stop Gateway

```ts
await openclaw.stopGateway()
logUnsub.remove()
exitUnsub.remove()
```

### Check State

```ts
const running = await openclaw.isRunning()
const setup = await openclaw.isSetupComplete()
const info = await openclaw.getRuntimeInfo()
const version = await openclaw.getNodeVersion()
```

## API Reference

### `openclaw` — Core API

| Method | Returns | Description |
|--------|---------|-------------|
| `runSetup()` | `Promise<{success, nodeVersion}>` | Full glibc + Node.js + OpenClaw setup |
| `isSetupComplete()` | `Promise<boolean>` | Check if setup has been completed |
| `startGateway(options?)` | `Promise<string>` | Start gateway with optional env vars |
| `stopGateway()` | `Promise<string>` | Kill gateway + children + cleanup |
| `isRunning()` | `Promise<boolean>` | Process alive check |
| `getNodeVersion()` | `Promise<string>` | Installed Node.js version |
| `verifyRuntime()` | `Promise<RuntimeVerify>` | Diagnostic info (glibc, node, openclaw) |
| `getRuntimeInfo()` | `Promise<RuntimeInfo>` | Full runtime environment info |
| `runCommand(cmd)` | `Promise<string>` | Run a shell command via Node.js |

### Vault (Optional)

| Method | Returns | Description |
|--------|---------|-------------|
| `initVault(path)` | `Promise<string>` | Create vault directory structure |
| `migrateToVault(path)` | `Promise<boolean>` | Copy workspace to vault |
| `getVaultPath()` | `Promise<string \| null>` | Get current vault path |
| `setVaultEnabled(bool)` | `Promise<boolean>` | Toggle vault |
| `isVaultEnabled()` | `Promise<boolean>` | Check vault enabled |

### Boot Auto-Start

| Method | Returns | Description |
|--------|---------|-------------|
| `setAutoStartOnBoot(bool)` | `Promise<boolean>` | Toggle boot auto-start |
| `getAutoStartOnBoot()` | `Promise<boolean>` | Check boot auto-start |

### Events

```ts
import { onSetupProgress, onLog, onGatewayExit } from 'react-native-openclaw'
```

| Event | Callback Signature | Description |
|-------|-------------------|-------------|
| `onSetupProgress` | `(progress: SetupProgress) => void` | Setup progress `{step, percent}` |
| `onLog` | `(line: string) => void` | Gateway stdout lines (batched 20/100ms) |
| `onGatewayExit` | `(code: number) => void` | Gateway process exit code |

Each returns `{ remove: () => void }` for unsubscribing.

### Types

```ts
interface StartOptions {
  envVars?: Record<string, string>
}

interface SetupProgress {
  step: string
  percent: number
}

interface RuntimeInfo {
  filesDir: string
  nativeLibDir: string
  glibcReady: boolean
  nodeReady: boolean
  openclawReady: boolean
  setupComplete: boolean
}

interface RuntimeVerify {
  glibcExists: boolean
  glibcExecutable: boolean
  nodeInstalled: boolean
  openclawInstalled: boolean
  setupComplete: boolean
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Consumer React Native App               │
│                                                         │
│  Consumer code imports:                                 │
│  import { openclaw, onLog } from 'react-native-openclaw'│
│                                                         │
│         ┌──────────────────┐                            │
│         │  openclaw.start  │                            │
│         │  onLog / onExit  │                            │
│         └────────┬─────────┘                            │
│                  │ NativeModules bridge                 │
├──────────────────┼──────────────────────────────────────┤
│                  ▼                                       │
│  OpenClawProcessModule (Kotlin native module)            │
│         │                                                │
│         ├── OpenClawRuntime.kt (process mgmt)            │
│         ├── OpenClawGatewayService.kt (foreground svc)   │
│         └── LocalConnectProxy.kt (npm DNS bypass)        │
│         │                                                │
├─────────┼────────────────────────────────────────────────┤
│         ▼  Android OS (Linux kernel + Bionic libc)       │
│                                                         │
│  Process: ld-linux → node.bin → openclaw gateway        │
└─────────────────────────────────────────────────────────┘
```

### The glibc-on-Android Problem

Android uses Bionic libc, but official Node.js binaries are compiled for glibc. This package solves it by bundling a glibc dynamic loader:

| Component | Location | Permission | Why |
|-----------|----------|------------|-----|
| `libld_linux.so` (loader) | `nativeLibraryDir` (JNI) | exec | Android extracts JNI libs to a dir with exec permission |
| glibc shared libs | `filesDir/glibc/` (APK assets) | read/mmap | Only need mmap for dlopen, not exec |
| Node.js binary | `filesDir/node/` (downloaded) | read/mmap | Loaded by ld-linux via mmap |
| `hijack.js` preload | `filesDir/` | read | Patches DNS, process.execPath, child_process |

### DNS Resolution

glibc's `getaddrinfo()` needs `/etc/resolv.conf` (doesn't exist on Android). The package uses a two-layer strategy:

1. **hijack.js c-ares patch** — patches `dns.lookup()` to use c-ares with explicit DNS servers (8.8.8.8, 1.1.1.1). Handles all runtime API calls.
2. **LocalConnectProxy** — HTTP CONNECT proxy for npm's TLS handshake during setup. Only runs when plugin deps aren't staged yet.

### Child Process Spawning

When Node.js runs via ld-linux, `process.execPath` returns the ld-linux path. The `hijack.js` preload script patches `process.execPath`, `child_process.spawn`, `child_process.execFile`, and `child_process.exec` to redirect node invocations through ld-linux.

## Environment Variables

Pass these via `startGateway({ envVars })`. OpenClaw reads them at runtime:

| Variable | Purpose |
|----------|---------|
| `ZAI_API_KEY` | GLM/z.ai model provider |
| `ANTHROPIC_API_KEY` | Anthropic models |
| `OPENAI_API_KEY` | OpenAI/Custom provider |
| `OPENROUTER_API_KEY` | OpenRouter |
| `GROQ_API_KEY` | Groq |
| `TELEGRAM_BOT_TOKEN` | Telegram channel |
| `MATTERMOST_BOT_TOKEN` | Mattermost channel |
| `MATTERMOST_BASE_URL` | Mattermost server URL |

## AndroidManifest

The library's manifest declares these (merged into your app automatically):

- Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`
- Service: `OpenClawGatewayService` (foreground, `specialUse` type)
- Receiver: `BootReceiver` (auto-start on boot, requires user opt-in via `setAutoStartOnBoot(true)`)

No manual manifest changes needed in your app.

## Known Limitations

- **arm64 only** — glibc libs and Node.js binary are aarch64-specific
- **No x86 emulator support** — use a physical device or arm64 emulator
- **Plugin staging** — some OpenClaw plugins need `npm install` on first start (a few seconds each)
- **Android 16 stricter SELinux** — handled by hijack.js spawn patching

## License

MIT
