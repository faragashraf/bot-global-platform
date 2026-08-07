import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { HomePlatformCategory } from '../../../../features/home/models/home-platform-category.model';

@Component({
  selector: 'bgp-platform-category-card',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './platform-category-card.component.html',
  styleUrl: './platform-category-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PlatformCategoryCardComponent {
  readonly category = input.required<HomePlatformCategory>();
}
