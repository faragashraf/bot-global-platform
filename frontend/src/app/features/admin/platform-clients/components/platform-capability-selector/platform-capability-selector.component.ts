import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CheckboxModule } from 'primeng/checkbox';
import { TagModule } from 'primeng/tag';

import {
  PlatformCapabilityDescriptor,
  PlatformCapabilityImpact,
} from '../../models/platform-client.models';

@Component({
  selector: 'app-platform-capability-selector',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    CheckboxModule,
    TagModule,
  ],
  templateUrl: './platform-capability-selector.component.html',
  styleUrl: './platform-capability-selector.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformCapabilitySelectorComponent {
  readonly capabilities =
    input.required<PlatformCapabilityDescriptor[]>();

  readonly selected =
    input.required<string[]>();

  readonly disabled =
    input(false);

  readonly selectionChange =
    output<string[]>();

  readonly selectedSet =
    computed(
      () =>
        new Set(
          this.selected().map(
            (value) => value.toLowerCase(),
          ),
        ),
    );

  isSelected(
    capability: string,
  ): boolean {
    return this.selectedSet().has(
      capability.toLowerCase(),
    );
  }

  toggle(
    descriptor: PlatformCapabilityDescriptor,
    enabled: boolean,
  ): void {
    if (this.disabled()) {
      return;
    }

    const normalized =
      descriptor.capability.toLowerCase();

    const next =
      this.selected()
        .filter(
          (item) =>
            item.toLowerCase() !== normalized,
        );

    if (enabled) {
      next.push(descriptor.capability);
    }

    this.selectionChange.emit(next);
  }

  impactSeverity(
    impact: PlatformCapabilityImpact,
  ): 'success' | 'warn' | 'danger' {
    switch (impact) {
      case 'High':
        return 'danger';

      case 'Medium':
        return 'warn';

      default:
        return 'success';
    }
  }

  trackByCapability(
    _index: number,
    descriptor: PlatformCapabilityDescriptor,
  ): string {
    return descriptor.capability;
  }
}
