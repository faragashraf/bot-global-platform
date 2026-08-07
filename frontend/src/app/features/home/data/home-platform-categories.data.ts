import { HomePlatformCategory } from '../models/home-platform-category.model';

export const HOME_PLATFORM_CATEGORIES: readonly HomePlatformCategory[] = [
  {
    id: 'apps',
    titleKey: 'home.explorePlatform.categories.apps.title',
    descriptionKey: 'home.explorePlatform.categories.apps.description',
    eyebrowKey: 'home.explorePlatform.categories.apps.eyebrow',
    icon: 'pi pi-mobile',
    route: '/apps',
    accent: 'blue'
  },
  {
    id: 'games',
    titleKey: 'home.explorePlatform.categories.games.title',
    descriptionKey: 'home.explorePlatform.categories.games.description',
    eyebrowKey: 'home.explorePlatform.categories.games.eyebrow',
    icon: 'pi pi-sparkles',
    route: '/games',
    accent: 'violet'
  },
  {
    id: 'programs',
    titleKey: 'home.explorePlatform.categories.programs.title',
    descriptionKey: 'home.explorePlatform.categories.programs.description',
    eyebrowKey: 'home.explorePlatform.categories.programs.eyebrow',
    icon: 'pi pi-desktop',
    route: '/programs',
    accent: 'cyan'
  }
];
