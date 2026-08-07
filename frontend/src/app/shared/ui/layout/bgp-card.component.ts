import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'bgp-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="bgp-card">
      <ng-content />
    </section>
  `
})
export class BgpCardComponent {}
