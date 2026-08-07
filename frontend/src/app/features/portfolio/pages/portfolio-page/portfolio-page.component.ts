import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  effect,
  inject
} from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { LanguageService } from '../../../../core/i18n/language.service';
import {
  getPortfolioData
} from '../../data/portfolio.data';
import type {
  PortfolioContent
} from '../../models/portfolio.models';

@Component({
  selector: 'bgp-portfolio-page',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './portfolio-page.component.html',
  styleUrl: './portfolio-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PortfolioPageComponent {
  private readonly languageService = inject(LanguageService);
  private readonly cdr = inject(ChangeDetectorRef);

  portfolio: PortfolioContent =
    getPortfolioData(this.languageService.language());

  featuredCaseStudies = this.portfolio.caseStudies;

  constructor() {
    effect(() => {
      const language = this.languageService.language();

      this.portfolio = getPortfolioData(language);

      // Full Portfolio: never truncate the migrated case studies.
      this.featuredCaseStudies = this.portfolio.caseStudies;

      this.cdr.markForCheck();
    });
  }

  isStringArray(
    value: string | readonly string[]
  ): value is readonly string[] {
    return Array.isArray(value);
  }
}
