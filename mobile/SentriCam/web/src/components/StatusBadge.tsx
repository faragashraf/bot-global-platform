type Props = {
  tone: 'positive' | 'warning' | 'danger' | 'neutral' | 'accent'
  children: React.ReactNode
  pulse?: boolean
}

export function StatusBadge({ tone, children, pulse = false }: Props) {
  return <span className={`badge badge--${tone}${pulse ? ' badge--pulse' : ''}`}>{children}</span>
}
