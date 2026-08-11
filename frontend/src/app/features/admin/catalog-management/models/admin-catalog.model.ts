export type AdminCatalogCategory = 'app' | 'game' | 'program';
export type AdminPublicationStatus = 'Draft' | 'Published' | 'Archived';

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
