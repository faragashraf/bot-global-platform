export type AdminCatalogCategory = 'app' | 'game' | 'program';
export type AdminPublicationStatus = 'Draft' | 'Published' | 'Archived';
export type AdminCatalogLinkType =
  | 'support'
  | 'privacy'
  | 'store'
  | 'download'
  | 'website';

export interface AdminCatalogProduct {
  readonly id: string;
  readonly slug: string;
  readonly category: AdminCatalogCategory;
  readonly publicationStatus: AdminPublicationStatus;
  readonly featured: boolean;
  readonly sortOrder: number;
  readonly publishedAtUtc: string | null;
  readonly nameEn: string;
  readonly nameAr: string;
}

export interface AdminCatalogProductsResponse {
  readonly items: readonly AdminCatalogProduct[];
  readonly total: number;
}

export interface AdminCatalogFilters {
  readonly search?: string;
  readonly category?: AdminCatalogCategory;
  readonly status?: AdminPublicationStatus;
  readonly featured?: boolean;
}

export interface AdminCatalogProductLocalization {
  readonly name: string;
  readonly shortDescription: string;
  readonly description: string;
  readonly displayStatus: string | null;
  readonly platforms: readonly string[];
  readonly technologies: readonly string[];
}

export interface AdminCatalogProductLocalizations {
  readonly en: AdminCatalogProductLocalization;
  readonly ar: AdminCatalogProductLocalization;
}

export interface AdminCatalogProductLink {
  readonly id: string;
  readonly type: AdminCatalogLinkType;
  readonly url: string;
  readonly labelEn: string | null;
  readonly labelAr: string | null;
  readonly sortOrder: number;
}

export interface AdminCatalogProductDetail extends AdminCatalogProduct {
  readonly localizations: AdminCatalogProductLocalizations;
  readonly links: readonly AdminCatalogProductLink[];
}

export interface AdminCatalogProductLinkRequest {
  readonly type: AdminCatalogLinkType;
  readonly url: string;
  readonly labelEn?: string;
  readonly labelAr?: string;
  readonly sortOrder: number;
}

export interface AdminCatalogProductWriteRequest {
  readonly slug: string;
  readonly category: AdminCatalogCategory;
  readonly featured: boolean;
  readonly sortOrder: number;
  readonly localizations: AdminCatalogProductLocalizations;
  readonly links: readonly AdminCatalogProductLinkRequest[];
}
