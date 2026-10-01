export function formatRelative(value: string | null): string {
  if (!value) return 'Never'
  const elapsed = Date.now() - new Date(value).getTime()
  if (elapsed < 5_000) return 'Just now'
  if (elapsed < 60_000) return `${Math.floor(elapsed / 1_000)}s ago`
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)}m ago`
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export function formatStorage(bytes: number | null): string {
  if (bytes === null) return 'Unknown'
  return `${(bytes / 1_073_741_824).toFixed(bytes < 10_737_418_240 ? 1 : 0)} GB`
}

export function formatBytes(bytes: number): string {
  if (bytes < 1_024) return `${bytes} B`
  if (bytes < 1_048_576) return `${(bytes / 1_024).toFixed(bytes < 10_240 ? 1 : 0)} KB`
  if (bytes < 1_073_741_824) return `${(bytes / 1_048_576).toFixed(bytes < 10_485_760 ? 1 : 0)} MB`
  return `${(bytes / 1_073_741_824).toFixed(1)} GB`
}

export function formatTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function readable(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replaceAll('_', ' ')
    .replace(/\b\w/g, (letter) => letter.toUpperCase())
}
