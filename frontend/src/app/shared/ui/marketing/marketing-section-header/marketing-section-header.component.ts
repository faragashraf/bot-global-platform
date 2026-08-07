import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'bgp-marketing-section-header',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './marketing-section-header.component.html',
  styleUrl: './marketing-section-header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MarketingSectionHeaderComponent {
  readonly eyebrowKey = input.required<string>();
  readonly titleKey = input.required<string>();
  readonly descriptionKey = input.required<string>();
  readonly align = input<'start' | 'center'>('start');
}
