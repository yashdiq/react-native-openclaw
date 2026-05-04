import { NativeModules, NativeEventEmitter, type EmitterSubscription } from 'react-native'
import type { SetupProgress } from './types'

const eventEmitter = new NativeEventEmitter(NativeModules.RuraProcess)

export function onSetupProgress(callback: (progress: SetupProgress) => void): { remove: () => void } {
  const sub = eventEmitter.addListener('RuraSetupProgress', callback)
  return { remove: () => sub.remove() }
}

export function onLog(callback: (line: string) => void): { remove: () => void } {
  const batchSub = eventEmitter.addListener('RuraLogBatch', (lines: string[]) => {
    for (const line of lines) callback(line)
  })
  const singleSub = eventEmitter.addListener('RuraLog', (line: string) => callback(line))
  return {
    remove: () => {
      batchSub.remove()
      singleSub.remove()
    },
  }
}

export function onGatewayExit(callback: (code: number) => void): { remove: () => void } {
  const sub = eventEmitter.addListener('RuraGatewayExit', callback)
  return { remove: () => sub.remove() }
}
