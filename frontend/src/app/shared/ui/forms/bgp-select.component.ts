import {
  ChangeDetectionStrategy,
  Component,
  forwardRef,
  input,
  signal
} from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { SelectModule } from 'primeng/select';

export interface BgpSelectOption {
  label: string;
  value: string | number | boolean | null;
}

let nextId = 0;

@Component({
  selector: 'bgp-select',
  standalone: true,
  imports: [FormsModule, SelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => BgpSelectComponent),
    multi: true
  }],
  template: `
    <div class="bgp-field">
      @if (label()) {
        <label class="bgp-field__label" [for]="controlId">
          {{ label() }}
          @if (required()) {
            <span class="bgp-field__required" aria-hidden="true">*</span>
          }
        </label>
      }

      <p-select
        styleClass="bgp-field__control"
        [inputId]="controlId"
        [options]="options()"
        optionLabel="label"
        optionValue="value"
        [placeholder]="placeholder()"
        [filter]="filterable()"
        [showClear]="clearable()"
        [disabled]="disabled()"
        [fluid]="true"
        [ngModel]="value()"
        (ngModelChange)="handleChange($event)"
        (onBlur)="handleBlur()"
      />

      @if (hint() && !invalid()) {
        <small class="bgp-field__hint">{{ hint() }}</small>
      }

      @if (invalid() && error()) {
        <small class="bgp-field__error">{{ error() }}</small>
      }
    </div>
  `
})
export class BgpSelectComponent implements ControlValueAccessor {
  readonly label = input<string>();
  readonly placeholder = input('');
  readonly hint = input<string>();
  readonly error = input<string>();
  readonly required = input(false);
  readonly invalid = input(false);
  readonly filterable = input(false);
  readonly clearable = input(false);
  readonly options = input<BgpSelectOption[]>([]);

  readonly value = signal<unknown>(null);
  readonly disabled = signal(false);
  readonly controlId = `bgp-select-${++nextId}`;

  private onChange: (value: unknown) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: unknown): void {
    this.value.set(value ?? null);
  }

  registerOnChange(fn: (value: unknown) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }

  handleChange(value: unknown): void {
    this.value.set(value);
    this.onChange(value);
  }

  handleBlur(): void {
    this.onTouched();
  }
}
