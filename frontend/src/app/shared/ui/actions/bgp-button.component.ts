import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ButtonModule } from 'primeng/button';

export type BgpButtonTone =
  | 'primary'
  | 'secondary'
  | 'success'
  | 'danger'
  | 'contrast';

@Component({
  selector: 'bgp-button',
  standalone: true,
  imports: [ButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p-button
      [label]="label()"
      [icon]="icon()"
      [iconPos]="iconPosition()"
      [severity]="tone()"
      [outlined]="outlined()"
      [text]="text()"
      [rounded]="rounded()"
      [loading]="loading()"
      [disabled]="disabled()"
      [fluid]="fluid()"
      (onClick)="pressed.emit()"
    />
  `
})
export class BgpButtonComponent {
  readonly label = input.required<string>();
  readonly icon = input<string>();
  readonly iconPosition = input<'left' | 'right' | 'top' | 'bottom'>('left');
  readonly tone = input<BgpButtonTone>('primary');
  readonly outlined = input(false);
  readonly text = input(false);
  readonly rounded = input(false);
  readonly loading = input(false);
  readonly disabled = input(false);
  readonly fluid = input(false);
  readonly pressed = output<void>();
}
