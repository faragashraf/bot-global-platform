import { Icon } from './Icon'

type IconName = Parameters<typeof Icon>[0]['name']

export function MetricCard({ icon, label, value, detail, tone = 'default' }: {
  icon: IconName
  label: string
  value: string
  detail?: string
  tone?: 'default' | 'positive' | 'warning'
}) {
  return (
    <article className={`metric-card metric-card--${tone}`}>
      <div className="metric-card__icon"><Icon name={icon} /></div>
      <div>
        <div className="eyebrow">{label}</div>
        <strong className="metric-card__value">{value}</strong>
        {detail && <p>{detail}</p>}
      </div>
    </article>
  )
}
