export interface SetupProgress {
  step: string
  percent: number
}

export interface RuntimeInfo {
  filesDir: string
  nativeLibDir: string
  glibcReady: boolean
  nodeReady: boolean
  openclawReady: boolean
  setupComplete: boolean
}

export interface RuntimeVerify {
  glibcExists: boolean
  glibcExecutable: boolean
  nodeInstalled: boolean
  openclawInstalled: boolean
  setupComplete: boolean
}

export interface StartOptions {
  envVars?: Record<string, string>
}

export interface LogEntry {
  id: string
  timestamp: number
  level: 'info' | 'warn' | 'error' | 'debug'
  source: 'gateway' | 'tool' | 'system'
  message: string
}
