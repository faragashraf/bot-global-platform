import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { ThemeService } from '../../core/theme/theme.service';
import { HOME_PLATFORM_CATEGORIES } from './data/home-platform-categories.data';
import { MarketingSectionHeaderComponent } from '../../shared/ui/marketing/marketing-section-header/marketing-section-header.component';
import { PlatformCategoryCardComponent } from '../../shared/ui/marketing/platform-category-card/platform-category-card.component';
import { CatalogEngine } from '../catalog/services/catalog-engine.service';
import type { CatalogProduct } from '../catalog/models/catalog.model';
import { ProductCardComponent } from '../../shared/ui/catalog/product-card.component';
interface CategoryCard {
  icon: string;
  titleKey: string;
  descriptionKey: string;
  route: string;
}

interface PortfolioCard {
  title: string;
  meta: string;
  icon: string;
}

@Component({
  selector: 'bgp-home-page',
  standalone: true,
  imports: [TranslateModule, RouterLink,
    MarketingSectionHeaderComponent,
    PlatformCategoryCardComponent,
    ProductCardComponent],
  templateUrl: './home-page.component.html',
  styleUrl: './home-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HomePageComponent {
  readonly platformCategories = HOME_PLATFORM_CATEGORIES;
  private readonly catalog = inject(CatalogEngine);

  readonly theme = inject(ThemeService);

  readonly categories: CategoryCard[] = [
    {
      icon: 'pi pi-mobile',
      titleKey: 'home.categories.mobile.title',
      descriptionKey: 'home.categories.mobile.description',
      route: '/apps'
    },
    {
      icon: 'pi pi-code',
      titleKey: 'home.categories.programs.title',
      descriptionKey: 'home.categories.programs.description',
      route: '/programs'
    },
    {
      icon: 'pi pi-discord',
      titleKey: 'home.categories.games.title',
      descriptionKey: 'home.categories.games.description',
      route: '/games'
    },
    {
      icon: 'pi pi-th-large',
      titleKey: 'home.categories.solutions.title',
      descriptionKey: 'home.categories.solutions.description',
      route: '/portfolio'
    }
  ];

  readonly products = computed(() => this.catalog.getFeaturedProducts(undefined, 4));
  readonly catalogLoading = this.catalog.isLoading;
  readonly catalogLoadFailed = this.catalog.hasLoadError;

  readonly portfolio: PortfolioCard[] = [
    { title: 'Enterprise Platform', meta: 'Web Application', icon: 'pi pi-chart-line' },
    { title: 'Space Explorer', meta: 'Game Concept', icon: 'pi pi-send' },
    { title: 'Health Tracker', meta: 'Mobile Application', icon: 'pi pi-heart' }
  ];

  protected getProductRoute(product: CatalogProduct): string {
    return this.catalog.getProductRoute(product);
  }
}
