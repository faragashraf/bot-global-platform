export function DashboardSkeleton() {
  return <div className="skeleton-grid" aria-label="Loading devices"><div className="skeleton skeleton--rail"/><div className="skeleton-stack"><div className="skeleton skeleton--hero"/><div className="skeleton-cards">{Array.from({ length: 5 }, (_, index) => <div className="skeleton skeleton--card" key={index}/>)}</div><div className="skeleton skeleton--panel"/></div></div>
}
