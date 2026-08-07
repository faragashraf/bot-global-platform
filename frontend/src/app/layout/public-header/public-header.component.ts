import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LanguageService } from '../../core/i18n/language.service';
import { ThemeService } from '../../core/theme/theme.service';
import { BgpBrandComponent } from '../../shared/ui/branding/bgp-brand.component';

@Component({
  selector: 'bgp-public-header',
  standalone: true,
  imports: [RouterLink, TranslateModule, BgpBrandComponent],
  templateUrl: './public-header.component.html',
  styleUrl: './public-header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicHeaderComponent {
  readonly theme = inject(ThemeService);
  readonly language = inject(LanguageService);
  readonly mobileOpen = signal(false);

  toggleMobile(): void {
    this.mobileOpen.update((value) => !value);
  }

  toggleTheme(): void {
    const current = this.theme.preference();
    this.theme.setPreference(current === 'dark' ? 'light' : 'dark');
  }
}
