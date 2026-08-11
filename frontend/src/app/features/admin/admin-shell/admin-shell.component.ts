import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal
} from '@angular/core';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet
} from '@angular/router';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { TranslateModule } from '@ngx-translate/core';
import { filter, map, startWith } from 'rxjs';

import { AuthService } from '../../../core/auth/auth.service';
import {
  LanguageService,
  SupportedLanguage
} from '../../../core/i18n/language.service';
import {
  ThemePreference,
  ThemeService
} from '../../../core/theme/theme.service';
import {
  ADMIN_NAVIGATION,
  ADMIN_SECTIONS,
  AdminSection
} from '../admin-navigation';

@Component({
  selector: 'bgp-admin-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, TranslateModule],
  templateUrl: './admin-shell.component.html',
  styleUrl: './admin-shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminShellComponent {
  readonly auth = inject(AuthService);
  readonly theme = inject(ThemeService);
  readonly language = inject(LanguageService);

  private readonly router = inject(Router);

  readonly mobileNavigationOpen = signal(false);
  readonly navigation = ADMIN_NAVIGATION;

  readonly currentSectionLabelKey = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      startWith(null),
      map(() => this.activeSection().labelKey)
    ),
    { initialValue: ADMIN_SECTIONS.dashboard.labelKey }
  );

  readonly themeOptions: readonly ThemePreference[] = [
    'system',
    'light',
    'dark'
  ];

  readonly initials = computed(() => {
    const name = this.auth.user()?.displayName?.trim();
    if (!name) return 'BG';

    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part.charAt(0).toUpperCase())
      .join('');
  });

  constructor() {
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed()
      )
      .subscribe(() => this.mobileNavigationOpen.set(false));
  }

  toggleMobileNavigation(): void {
    this.mobileNavigationOpen.update((value) => !value);
  }

  closeMobileNavigation(): void {
    this.mobileNavigationOpen.set(false);
  }

  setTheme(preference: ThemePreference): void {
    this.theme.setPreference(preference);
  }

  setLanguage(language: SupportedLanguage): void {
    this.language.setLanguage(language);
  }

  async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }

  private activeSection(): AdminSection {
    const currentPath = this.router.url
      .split(/[?#]/, 1)[0]
      .replace(/\/$/, '');

    return [...ADMIN_NAVIGATION]
      .sort((left, right) => right.route.length - left.route.length)
      .find((section) =>
        section.exact
          ? currentPath === section.route
          : currentPath === section.route ||
            currentPath.startsWith(`${section.route}/`)
      ) ?? ADMIN_SECTIONS.dashboard;
  }
}
