import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { PublicFooterComponent } from '../../layout/public-footer/public-footer.component';
import { PublicHeaderComponent } from '../../layout/public-header/public-header.component';

import { ThemeService } from '../../core/theme/theme.service';
interface CategoryCard {
  icon: string;
  titleKey: string;
  descriptionKey: string;
}

interface ProductCard {
  title: string;
  meta: string;
  badge: string;
  gradient: string;
}

interface PortfolioCard {
  title: string;
  meta: string;
  icon: string;
}

@Component({
  selector: 'bgp-home-page',
  standalone: true,
  imports: [TranslateModule, PublicHeaderComponent, PublicFooterComponent],
  templateUrl: './home-page.component.html',
  styleUrl: './home-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HomePageComponent {
  readonly theme = inject(ThemeService);

  readonly categories: CategoryCard[] = [
    {
      icon: 'pi pi-mobile',
      titleKey: 'home.categories.mobile.title',
      descriptionKey: 'home.categories.mobile.description'
    },
    {
      icon: 'pi pi-code',
      titleKey: 'home.categories.programs.title',
      descriptionKey: 'home.categories.programs.description'
    },
    {
      icon: 'pi pi-discord',
      titleKey: 'home.categories.games.title',
      descriptionKey: 'home.categories.games.description'
    },
    {
      icon: 'pi pi-th-large',
      titleKey: 'home.categories.solutions.title',
      descriptionKey: 'home.categories.solutions.description'
    }
  ];

  readonly products: ProductCard[] = [
    { title: 'SentriCam', meta: 'Mobile Security', badge: 'NEW', gradient: 'blue' },
    { title: 'Bot Dashboard', meta: 'Analytics Platform', badge: 'PRO', gradient: 'violet' },
    { title: 'TaskFlow', meta: 'Productivity', badge: 'NEW', gradient: 'cyan' },
    { title: 'PlayZone', meta: 'Games', badge: 'NEW', gradient: 'orange' }
  ];

  readonly portfolio: PortfolioCard[] = [
    { title: 'Enterprise Platform', meta: 'Web Application', icon: 'pi pi-chart-line' },
    { title: 'Space Explorer', meta: 'Game Concept', icon: 'pi pi-send' },
    { title: 'Health Tracker', meta: 'Mobile Application', icon: 'pi pi-heart' }
  ];
}
