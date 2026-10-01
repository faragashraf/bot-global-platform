export function monitoringTrace(event: string, fields: Record<string, unknown> = {}) {
  if (!import.meta.env.DEV || import.meta.env.MODE === 'test') return
  const payload = {
    scope: 'monitoring',
    event,
    browserAtUtc: new Date().toISOString(),
    ...fields,
  }
  console.debug(JSON.stringify(payload))
  void fetch('/api/v1/development/monitoring-trace', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    keepalive: true,
  }).catch(() => undefined)
}
