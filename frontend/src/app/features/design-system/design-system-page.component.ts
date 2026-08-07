import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { LanguageService } from '../../core/i18n/language.service';
import { ThemePreference, ThemeService } from '../../core/theme/theme.service';
import { BgpButtonComponent } from '../../shared/ui/actions/bgp-button.component';
import { BgpBadgeComponent } from '../../shared/ui/data-display/bgp-badge.component';
import { BgpInputComponent } from '../../shared/ui/forms/bgp-input.component';
import { BgpSelectComponent, BgpSelectOption } from '../../shared/ui/forms/bgp-select.component';
import { BgpCardComponent } from '../../shared/ui/layout/bgp-card.component';

@Component({
  selector: 'bgp-design-system-page',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    BgpButtonComponent,
    BgpBadgeComponent,
    BgpInputComponent,
    BgpSelectComponent,
    BgpCardComponent
  ],
  templateUrl: './design-system-page.component.html',
  styleUrl: './design-system-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DesignSystemPageComponent {
  readonly theme = inject(ThemeService);
  readonly language = inject(LanguageService);

  displayName = '';
  selectedApplication: string | null = null;

  readonly applications: BgpSelectOption[] = [
    { label: 'SentriCam', value: 'sentricam' },
    { label: 'Bot Global Platform', value: 'bot-global' },
    { label: 'WhatsApp Platform', value: 'whatsapp' }
  ];

  setTheme(theme: ThemePreference): void {
    this.theme.setPreference(theme);
  }
}
