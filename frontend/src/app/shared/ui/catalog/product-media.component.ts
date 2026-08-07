import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { LanguageService } from '../../../core/i18n/language.service';
import {
  type CatalogMediaReference,
  getLocalizedText
} from '../../../features/catalog/models/catalog.model';

@Component({
  selector: 'bgp-product-media',
  standalone: true,
  template: `
    <section class="catalog-product-media">
      @if (heroMedia(); as hero) {
        <figure class="catalog-product-media__hero">
          <img
            [src]="hero.url"
            [alt]="heroAlt()"
            loading="eager"
            decoding="async"
          />
        </figure>
      }

      @if (screenshots().length > 0) {
        <div class="catalog-product-media__screenshots">
          @for (screenshot of screenshots(); track screenshot.url) {
            <img
              class="catalog-product-media__screenshot"
              [src]="screenshot.url"
              [alt]="getMediaAlt(screenshot)"
              loading="lazy"
              decoding="async"
            />
          }
        </div>
      }
    </section>
  `,
  styleUrl: './product-media.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProductMediaComponent {
  private readonly language = inject(LanguageService);

  readonly heroMedia = input.required<CatalogMediaReference>();
  readonly screenshots = input<readonly CatalogMediaReference[]>([]);

  readonly heroAlt = computed(() => {
    const value = this.heroMedia().alt;
    if (!value) {
      return '';
    }
    return getLocalizedText(value, this.language.language());
  });

  protected getMediaAlt(media: CatalogMediaReference): string {
    return media.alt ? getLocalizedText(media.alt, this.language.language()) : '';
  }
}
