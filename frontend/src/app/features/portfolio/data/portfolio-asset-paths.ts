export const PORTFOLIO_ASSET_ROOT = '/portfolio';

function normalizeString(value: string): string {
  const prefixes = ['/assets/', './assets/', 'assets/'];

  for (const prefix of prefixes) {
    if (value.startsWith(prefix)) {
      return `${PORTFOLIO_ASSET_ROOT}/${value.slice(prefix.length)}`;
    }
  }

  return value;
}

export function normalizePortfolioAssetPaths<T>(value: T): T {
  if (typeof value === 'string') {
    return normalizeString(value) as T;
  }

  if (Array.isArray(value)) {
    return value.map((item) =>
      normalizePortfolioAssetPaths(item)
    ) as T;
  }

  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .map(([key, item]) => [
          key,
          normalizePortfolioAssetPaths(item)
        ])
    ) as T;
  }

  return value;
}
