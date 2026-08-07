import {
  ChangeDetectionStrategy,
  Component,
  forwardRef,
  input,
  signal
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { InputTextModule } from 'primeng/inputtext';

let nextId = 0;

@Component({
  selector: 'bgp-input',
  standalone: true,
  imports: [InputTextModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => BgpInputComponent),
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

      <input
        pInputText
        class="bgp-field__control"
        [id]="controlId"
        [type]="type()"
        [placeholder]="placeholder()"
        [disabled]="disabled()"
        [attr.aria-invalid]="invalid()"
        [value]="value()"
        (input)="handleInput($event)"
        (blur)="handleBlur()"
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
export class BgpInputComponent implements ControlValueAccessor {
  readonly label = input<string>();
  readonly placeholder = input('');
  readonly hint = input<string>();
  readonly error = input<string>();
  readonly required = input(false);
  readonly invalid = input(false);
  readonly type = input<'text' | 'email' | 'password' | 'search' | 'tel' | 'url'>('text');

  readonly value = signal('');
  readonly disabled = signal(false);
  readonly controlId = `bgp-input-${++nextId}`;

  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: string | null | undefined): void {
    this.value.set(value ?? '');
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }

  handleInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.value.set(value);
    this.onChange(value);
  }

  handleBlur(): void {
    this.onTouched();
  }
}
