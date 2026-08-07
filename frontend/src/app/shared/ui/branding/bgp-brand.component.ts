import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'bgp-brand',
  standalone: true,
  template: `
    <a
      class="bgp-brand"
      [class.bgp-brand--compact]="compact()"
      href="/"
      aria-label="Bot Global home"
    >
      <img
        class="bgp-brand__mark"
        src="/brand/bot-global-approved-mark.png"
        alt=""
      />

      <span class="bgp-brand__text">
        <strong>BOT GLOBAL</strong>
        @if (!compact()) {
          <small>{{ subtitle() }}</small>
        }
      </span>
    </a>
  `,
  styles: [`
    .bgp-brand {
      display: inline-flex;
      align-items: center;
      gap: .72rem;
      color: inherit;
      text-decoration: none;
    }

    .bgp-brand__mark {
      width: 2.7rem;
      height: 2.7rem;
      object-fit: contain;
      flex: 0 0 auto;
      transform: scale(1.08);
      filter:
        drop-shadow(0 0 7px color-mix(in srgb, var(--bgp-cyan) 22%, transparent))
        drop-shadow(0 0 12px color-mix(in srgb, var(--bgp-blue) 12%, transparent));
    }

    :host-context(.bgp-dark-mode) .bgp-brand__mark {
      filter:
        drop-shadow(0 0 7px color-mix(in srgb, var(--bgp-cyan) 38%, transparent))
        drop-shadow(0 0 15px color-mix(in srgb, var(--bgp-blue) 24%, transparent));
    }

    .bgp-brand__text {
      display: grid;
      gap: .05rem;
      line-height: 1.1;
    }

    .bgp-brand__text strong {
      font-size: .94rem;
      font-weight: 800;
      letter-spacing: .08em;
      white-space: nowrap;
    }

    .bgp-brand__text small {
      margin-top: .16rem;
      color: var(--bgp-text-muted);
      font-size: .66rem;
      white-space: nowrap;
    }

    .bgp-brand--compact .bgp-brand__mark {
      width: 2.15rem;
      height: 2.15rem;
    }

    @media (max-width: 560px) {
      .bgp-brand__text small {
        display: none;
      }
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BgpBrandComponent {
  readonly compact = input(false);
  readonly subtitle = input('Building Intelligent Digital Solutions');
}
