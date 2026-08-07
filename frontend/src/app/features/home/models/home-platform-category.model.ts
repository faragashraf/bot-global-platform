import { type PublicCatalogCategoryPath } from '../../catalog/models/catalog.model';

export type HomePlatformCategoryAccent = 'blue' | 'violet' | 'cyan';

export interface HomePlatformCategory {
  readonly id: PublicCatalogCategoryPath;
  readonly titleKey: string;
  readonly descriptionKey: string;
  readonly eyebrowKey: string;
  readonly icon: string;
  readonly route: string;
  readonly accent: HomePlatformCategoryAccent;
}
