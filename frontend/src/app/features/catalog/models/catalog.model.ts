import type { SupportedLanguage } from '../../../core/i18n/language.service';

export type PublicCatalogCategory = 'app' | 'game' | 'program';
export type PublicCatalogCategoryPath = 'apps' | 'games' | 'programs';

export interface LocalizedText {
  readonly en: string;
  readonly ar: string;
}

export interface CatalogMediaReference {
  readonly url: string;
  readonly alt?: LocalizedText;
}

export type CatalogProductLinkType =
  | 'support'
  | 'privacy'
  | 'store'
  | 'download'
  | 'website';

export interface CatalogProductLink {
  readonly type: CatalogProductLinkType;
  readonly url: string;
  readonly label?: LocalizedText;
}

export interface CatalogProduct {
  readonly id: string;
  readonly slug: string;
  readonly category: PublicCatalogCategory;
  readonly featured: boolean;
  readonly name: LocalizedText;
  readonly shortDescription: LocalizedText;
  readonly description: LocalizedText;
  readonly status?: LocalizedText;
  readonly platforms: readonly LocalizedText[];
  readonly technologies: readonly LocalizedText[];
  readonly heroMedia?: CatalogMediaReference;
  readonly screenshots: readonly CatalogMediaReference[];
  readonly links: readonly CatalogProductLink[];
}

export interface CatalogCategoryDefinition {
  readonly path: PublicCatalogCategoryPath;
  readonly titleKey: string;
  readonly descriptionKey: string;
}

export const CATALOG_CATEGORIES = ['app', 'game', 'program'] as const satisfies readonly PublicCatalogCategory[];

export const CATALOG_CATEGORY_PATHS = ['apps', 'games', 'programs'] as const satisfies readonly PublicCatalogCategoryPath[];

export const CATALOG_PRODUCT_LINK_TYPES = [
  'support',
  'privacy',
  'store',
  'download',
  'website'
] as const satisfies readonly CatalogProductLinkType[];

export const CATALOG_CATEGORY_DEFINITIONS = {
  app: {
    path: 'apps',
    titleKey: 'catalog.categories.apps.title',
    descriptionKey: 'catalog.categories.apps.description'
  },
  game: {
    path: 'games',
    titleKey: 'catalog.categories.games.title',
    descriptionKey: 'catalog.categories.games.description'
  },
  program: {
    path: 'programs',
    titleKey: 'catalog.categories.programs.title',
    descriptionKey: 'catalog.categories.programs.description'
  }
} as const satisfies Record<PublicCatalogCategory, CatalogCategoryDefinition>;

export const CATALOG_CATEGORY_BY_PATH: Record<PublicCatalogCategoryPath, PublicCatalogCategory> = {
  apps: 'app',
  games: 'game',
  programs: 'program'
};

export function resolveCatalogCategory(category: unknown): PublicCatalogCategory | null {
  return category === 'app' || category === 'game' || category === 'program'
    ? category
    : null;
}

export function getLocalizedText(
  value: LocalizedText,
  language: SupportedLanguage
): string {
  return language === 'ar' ? value.ar : value.en;
}
