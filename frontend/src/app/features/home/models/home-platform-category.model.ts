export type HomePlatformCategoryAccent = 'blue' | 'violet' | 'cyan';

export interface HomePlatformCategory {
  readonly id: 'apps' | 'games' | 'programs';
  readonly titleKey: string;
  readonly descriptionKey: string;
  readonly eyebrowKey: string;
  readonly icon: string;
  readonly route: string;
  readonly accent: HomePlatformCategoryAccent;
}
