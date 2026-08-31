import * as signalR from '@microsoft/signalr'
import { monitoringTrace } from './monitoringDiagnostics'

export type RealtimeState = 'connecting' | 'live' | 'reconnecting' | 'offline'

// Realtime events are authoritative. This interval is only a bounded recovery path for a
// missed browser event, a sleeping tab, or a temporarily unavailable Hub connection.
export const OPERATIONAL_FALLBACK_POLL_MILLIS = 5_000

export class CappedSignalRRetryPolicy implements signalR.IRetryPolicy {
  constructor(private readonly random: () => number = Math.random) {}

  nextRetryDelayInMilliseconds(context: signalR.RetryContext): number {
    const delays = [0, 1_000, 2_000, 5_000, 10_000]
    const base = delays[Math.min(context.previousRetryCount, delays.length - 1)]
    if (base === 0) return 0
    return Math.round(base * (0.8 + this.random() * 0.4))
  }
}

export function createMonitoringConnection(
  accessToken: () => string,
  onState: (state: RealtimeState) => void,
  onDeviceChanged: (deviceId: string, serverUtcNow?: string) => void,
): signalR.HubConnection {
  const connection = new signalR.HubConnectionBuilder()
    .withUrl('/hubs/monitoring', { accessTokenFactory: accessToken })
    .withAutomaticReconnect(new CappedSignalRRetryPolicy())
    .configureLogging(signalR.LogLevel.None)
    .build()

  connection.on('DeviceChanged', (update: { deviceId: string; serverUtcNow?: string }) => {
    monitoringTrace('device_changed_received', update)
    onDeviceChanged(update.deviceId, update.serverUtcNow)
  })
  connection.on('CommandChanged', (update: { deviceId: string }) => onDeviceChanged(update.deviceId))
  connection.on('CameraControlChanged', (update: { deviceId: string }) => onDeviceChanged(update.deviceId))
  connection.onreconnecting(() => onState('reconnecting'))
  connection.onreconnected(() => onState('live'))
  connection.onclose(() => onState('offline'))
  onState('connecting')
  return connection
}
