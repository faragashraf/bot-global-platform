import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'bgp-empty-catalog-state',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  template: `
    <section class="catalog-empty" [attr.aria-live]="live() ? 'polite' : null">
      <h2>{{ titleKey() | translate }}</h2>
      <p>{{ descriptionKey() | translate }}</p>
      @if (actionLink() && actionLabelKey()) {
        <a class="catalog-empty__action" [routerLink]="actionLink()">
          {{ actionLabelKey() | translate }}
        </a>
      }
    </section>
  `,
  styleUrl: './empty-catalog-state.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EmptyCatalogStateComponent {
  readonly titleKey = input('catalog.empty.title');
  readonly descriptionKey = input('catalog.empty.description');
  readonly actionLink = input<string | null>(null);
  readonly actionLabelKey = input<string | null>(null);
  readonly live = input(false);
}
