import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { LanguageService } from '../../../core/i18n/language.service';
import { BgpBadgeComponent } from '../data-display/bgp-badge.component';
import { CatalogProduct, getLocalizedText } from '../../../features/catalog/models/catalog.model';

@Component({
  selector: 'bgp-product-card',
  standalone: true,
  imports: [BgpBadgeComponent, RouterLink, TranslateModule],
  template: `
    <article class="catalog-product-card">
      <a
        class="catalog-product-card__visual"
        [routerLink]="route()"
      >
        <span class="catalog-product-card__visual-fallback" aria-hidden="true"></span>
        @if (product().heroMedia; as heroMedia) {
          <img
            class="catalog-product-card__visual-image"
            [src]="heroMedia.url"
            [alt]="heroAlt()"
            loading="lazy"
          />
        } @else {
          <span class="catalog-product-card__visual-placeholder">
            {{ name() }}
          </span>
        }
      </a>

      <div class="catalog-product-card__body">
        <div class="catalog-product-card__meta">
          @if (status()) {
            <bgp-badge [value]="status() ?? ''" [severity]="'info'" />
          }
        </div>

        <div>
          <strong>{{ name() }}</strong>
          @if (showShortDescription()) {
            <small>{{ shortDescription() }}</small>
          }
        </div>

        @if (showPlatforms() && platforms().length) {
          <div
            class="catalog-product-card__chips"
            [attr.aria-label]="'catalog.detail.platforms' | translate"
          >
            @for (platform of platforms(); track platform) {
              <span class="catalog-product-card__chip">{{ platform }}</span>
            }
          </div>
        }

        @if (showTechnologies() && technologies().length) {
          <div
            class="catalog-product-card__chips"
            [attr.aria-label]="'catalog.detail.technologies' | translate"
          >
            @for (technology of technologies(); track technology) {
              <span class="catalog-product-card__chip">{{ technology }}</span>
            }
          </div>
        }

        <a
          class="catalog-product-card__cta"
          [routerLink]="route()"
          [attr.aria-label]="'catalog.actions.openProduct' | translate: { product: name() }"
        >
          {{ 'catalog.actions.openProduct' | translate: { product: name() } }}
          <i class="pi pi-arrow-right" aria-hidden="true"></i>
        </a>
      </div>
    </article>
  `,
  styleUrl: './product-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProductCardComponent {
  private readonly language = inject(LanguageService);

  readonly product = input.required<CatalogProduct>();
  readonly route = input.required<string>();
  readonly showShortDescription = input(true);
  readonly showPlatforms = input(false);
  readonly showTechnologies = input(false);

  readonly name = computed(() => {
    const value = getLocalizedText(this.product().name, this.language.language());
    return value;
  });

  readonly shortDescription = computed(() => {
    return getLocalizedText(this.product().shortDescription, this.language.language());
  });

  readonly status = computed(() => {
    const current = this.product().status;
    return current ? getLocalizedText(current, this.language.language()) : null;
  });

  readonly heroAlt = computed(() => {
    const current = this.product().heroMedia?.alt;
    if (!current) {
      return this.name();
    }

    return getLocalizedText(current, this.language.language());
  });

  readonly platforms = computed(() => {
    return this.product().platforms
      .map((platform) => getLocalizedText(platform, this.language.language()));
  });

  readonly technologies = computed(() => {
    return this.product().technologies
      .map((technology) => getLocalizedText(technology, this.language.language()));
  });
}
