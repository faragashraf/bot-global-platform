import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TagModule } from 'primeng/tag';

@Component({
  selector: 'bgp-badge',
  standalone: true,
  imports: [TagModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p-tag
      [value]="value()"
      [severity]="severity()"
      [rounded]="rounded()"
    />
  `
})
export class BgpBadgeComponent {
  readonly value = input.required<string>();
  readonly severity = input<'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast'>('info');
  readonly rounded = input(true);
}
