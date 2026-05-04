import { NativeOpenClaw } from './native-module'
import type { RuntimeInfo, RuntimeVerify, StartOptions } from './types'

export const openclaw = {
  runSetup(): Promise<{ success: boolean; nodeVersion: string }> {
    return NativeOpenClaw.runSetup()
  },

  isSetupComplete(): Promise<boolean> {
    return NativeOpenClaw.isSetupComplete()
  },

  isNodeInstalled(): Promise<boolean> {
    return NativeOpenClaw.isNodeInstalled()
  },

  getNodeVersion(): Promise<string> {
    return NativeOpenClaw.getNodeVersion()
  },

  startGateway(options?: StartOptions): Promise<string> {
    if (options?.envVars && Object.keys(options.envVars).length > 0) {
      return NativeOpenClaw.startWithEnv(options.envVars)
    }
    return NativeOpenClaw.start()
  },

  stopGateway(): Promise<string> {
    return NativeOpenClaw.stop()
  },

  isRunning(): Promise<boolean> {
    return NativeOpenClaw.isRunning()
  },

  verifyRuntime(): Promise<RuntimeVerify> {
    return NativeOpenClaw.verifyRuntime()
  },

  getRuntimeInfo(): Promise<RuntimeInfo> {
    return NativeOpenClaw.getRuntimeInfo()
  },

  runCommand(command: string): Promise<string> {
    return NativeOpenClaw.runInAlpine(command)
  },

  // Vault
  initVault(path: string): Promise<string> {
    return NativeOpenClaw.initVault(path)
  },

  migrateToVault(path: string): Promise<boolean> {
    return NativeOpenClaw.migrateToVault(path)
  },

  getVaultPath(): Promise<string | null> {
    return NativeOpenClaw.getVaultPath()
  },

  setVaultEnabled(enabled: boolean): Promise<boolean> {
    return NativeOpenClaw.setVaultEnabled(enabled)
  },

  isVaultEnabled(): Promise<boolean> {
    return NativeOpenClaw.isVaultEnabled()
  },

  // Boot auto-start
  setAutoStartOnBoot(enabled: boolean): Promise<boolean> {
    return NativeOpenClaw.setAutoStartOnBoot(enabled)
  },

  getAutoStartOnBoot(): Promise<boolean> {
    return NativeOpenClaw.getAutoStartOnBoot()
  },
}
