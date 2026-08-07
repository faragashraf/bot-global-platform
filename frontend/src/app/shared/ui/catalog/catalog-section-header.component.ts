import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'bgp-catalog-section-header',
  standalone: true,
  imports: [TranslateModule],
  template: `
    <header class="catalog-section-header">
      <h1>{{ titleKey() | translate }}</h1>
      <p class="catalog-section-header__description">
        {{ descriptionKey() | translate }}
      </p>
      @if (count() !== null) {
        <p class="catalog-section-header__count">
          {{ 'catalog.listing.count' | translate: { count: count() } }}
        </p>
      }
    </header>
  `,
  styleUrl: './catalog-section-header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CatalogSectionHeaderComponent {
  readonly titleKey = input.required<string>();
  readonly descriptionKey = input.required<string>();
  readonly count = input<number | null>(null);
}
