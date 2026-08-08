import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  AfterViewInit,
  OnDestroy,
  effect,
  signal,
  inject
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { LanguageService } from '../../../../core/i18n/language.service';
import {
  getPortfolioData
} from '../../data/portfolio.data';
import { PORTFOLIO_ASSET_ROOT } from '../../data/portfolio-asset-paths';
import type {
  PortfolioContent
} from '../../models/portfolio.models';

const normalizeTechnology = (value: string): string =>
  value.trim().toLocaleLowerCase().replace(/\s+/g, ' ');

const technologyIconMap = new Map([
  ['c#', `${PORTFOLIO_ASSET_ROOT}/logos/csharp.svg`],
  ['.net', `${PORTFOLIO_ASSET_ROOT}/logos/dotnet.svg`],
  ['asp.net core', `${PORTFOLIO_ASSET_ROOT}/logos/dotnet.svg`],
  ['rest apis', `${PORTFOLIO_ASSET_ROOT}/logos/rest-api.svg`],
  ['sql server', `${PORTFOLIO_ASSET_ROOT}/logos/sql-server.svg`],
  ['oracle', `${PORTFOLIO_ASSET_ROOT}/logos/oracle.svg`],
  ['angular', `${PORTFOLIO_ASSET_ROOT}/logos/angular.svg`],
  ['typescript', `${PORTFOLIO_ASSET_ROOT}/logos/typescript.svg`],
  ['html', `${PORTFOLIO_ASSET_ROOT}/logos/html5.svg`],
  ['html5', `${PORTFOLIO_ASSET_ROOT}/logos/html5.svg`],
  ['css', `${PORTFOLIO_ASSET_ROOT}/logos/css.svg`],
  ['iis', `${PORTFOLIO_ASSET_ROOT}/logos/iis.svg`],
  ['git', `${PORTFOLIO_ASSET_ROOT}/logos/git.svg`],
  ['postman', `${PORTFOLIO_ASSET_ROOT}/logos/postman.svg`],
  ['performance optimization', `${PORTFOLIO_ASSET_ROOT}/logos/performance.svg`],
  ['تحسين الأداء', `${PORTFOLIO_ASSET_ROOT}/logos/performance.svg`],
  ['system analysis', `${PORTFOLIO_ASSET_ROOT}/logos/analysis.svg`],
  ['تحليل النظم', `${PORTFOLIO_ASSET_ROOT}/logos/analysis.svg`]
]);

const portfolioSectionNavigation = [
  { id: 'about', label: 'portfolio.about.eyebrow' },
  { id: 'work', label: 'portfolio.projects.eyebrow' },
  { id: 'technology', label: 'portfolio.stack.eyebrow' },
  { id: 'experience', label: 'portfolio.experience.eyebrow' },
  { id: 'certifications', label: 'portfolio.certifications.eyebrow' },
  { id: 'contact', label: 'portfolio.contact.eyebrow' }
] as const;

type PortfolioSectionId = (typeof portfolioSectionNavigation)[number]['id'];

function getHashFromString(value: string): string | null {
  const hash = value.replace(/^#/, '').trim();
  return hash.length ? hash : null;
}

function parsePixels(value: string): number | null {
  const numeric = Number.parseFloat(value);
  return Number.isNaN(numeric) ? null : numeric;
}

function hasSectionId(
  sectionId: string
): sectionId is PortfolioSectionId {
  return portfolioSectionNavigation.some((entry) => entry.id === sectionId);
}

@Component({
  selector: 'bgp-portfolio-page',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './portfolio-page.component.html',
  styleUrl: './portfolio-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PortfolioPageComponent implements AfterViewInit, OnDestroy {
  private readonly languageService = inject(LanguageService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly document = inject(DOCUMENT);
  private readonly prefersReducedMotion = this.document.defaultView?.matchMedia(
    '(prefers-reduced-motion: reduce)'
  ).matches ?? false;

  readonly sectionNavigation = portfolioSectionNavigation;
  readonly activeSectionId = signal<PortfolioSectionId>('about');

  portfolio: PortfolioContent =
    getPortfolioData(this.languageService.language());

  featuredCaseStudies = this.portfolio.caseStudies;
  private sectionObserver: IntersectionObserver | null = null;
  private readonly sectionElements = new Map<PortfolioSectionId, HTMLElement>();

  constructor() {
    effect(() => {
      const language = this.languageService.language();

      this.portfolio = getPortfolioData(language);

      // Full Portfolio: never truncate the migrated case studies.
      this.featuredCaseStudies = this.portfolio.caseStudies;

      this.cdr.markForCheck();
    });
  }

  ngAfterViewInit(): void {
    this.initializeSectionNavigation();
    const initialHash = getHashFromString(this.document.location.hash);
    if (initialHash && hasSectionId(initialHash)) {
      this.scrollToSection(initialHash, /* forceSmooth */ false);
    }
  }

  ngOnDestroy(): void {
    this.sectionObserver?.disconnect();
    this.sectionObserver = null;
  }

  getSectionHref(sectionId: PortfolioSectionId): string {
    return `/portfolio#${sectionId}`;
  }

  onSectionSelect(sectionId: PortfolioSectionId): void {
    this.scrollToSection(sectionId, true);
  }

  onSectionLinkClick(
    sectionId: PortfolioSectionId,
    event: MouseEvent
  ): void {
    event.preventDefault();
    this.onSectionSelect(sectionId);

    if (!this.document.defaultView) return;
    const url = this.getSectionHref(sectionId);
    this.document.defaultView.history.pushState({}, '', url);
  }

  isStringArray(
    value: string | readonly string[]
  ): value is readonly string[] {
    return Array.isArray(value);
  }

  getTechnologyIcon(
    technology: string
  ): string | null {
    return technologyIconMap.get(normalizeTechnology(technology)) ?? null;
  }

  private initializeSectionNavigation(): void {
    const windowRef = this.document.defaultView;
    if (!windowRef || !('IntersectionObserver' in windowRef)) return;

    const sectionElements = Array.from(
      this.document.querySelectorAll<HTMLElement>('.portfolio-section[data-portfolio-section]')
    );
    const ids = new Set(portfolioSectionNavigation.map((section) => section.id));

    this.sectionElements.clear();
    for (const section of sectionElements) {
      if (!hasSectionId(section.id)) continue;

      this.sectionElements.set(section.id, section);
    }

    const activeSectionOnInit = getHashFromString(this.document.location.hash);
    if (activeSectionOnInit && hasSectionId(activeSectionOnInit)) {
      this.activeSectionId.set(activeSectionOnInit);
    } else {
      this.activeSectionId.set(portfolioSectionNavigation[0].id);
    }

    const stickyOffset = this.getStickyOffsetPx(windowRef);
    this.sectionObserver = new IntersectionObserver(
      (entries) => this.updateActiveSection(entries),
      {
        root: null,
        rootMargin: `-${stickyOffset}px 0px -45% 0px`,
        threshold: [0, 0.15, 0.35, 0.55, 0.75, 1]
      }
    );

    for (const section of this.sectionElements.values()) {
      this.sectionObserver.observe(section);
    }

    this.cdr.markForCheck();
  }

  private updateActiveSection(
    entries: IntersectionObserverEntry[]
  ): void {
    const visible = entries.filter((entry) => entry.isIntersecting);

    if (!visible.length) return;

    const sorted = visible.sort((left, right) => {
      const leftTop = Math.abs(left.boundingClientRect.top);
      const rightTop = Math.abs(right.boundingClientRect.top);

      if (leftTop === rightTop) {
        return right.intersectionRatio - left.intersectionRatio;
      }

      return leftTop - rightTop;
    });

    const topSection = sorted[0]?.target as HTMLElement | undefined;
    if (!topSection?.id) return;
    const nextSectionId = topSection.id;

    if (!hasSectionId(nextSectionId)) return;
    this.setActiveSection(nextSectionId);
  }

  private scrollToSection(
    sectionId: PortfolioSectionId,
    forceSmooth = true
  ): void {
    const windowRef = this.document.defaultView;
    const section = this.sectionElements.get(sectionId);

    if (!windowRef || !section) return;

    const targetTop = section.getBoundingClientRect().top + windowRef.scrollY;
    const offsetTop = Math.max(
      0,
      Math.floor(targetTop - this.getStickyOffsetPx(windowRef) - 8)
    );

    windowRef.scrollTo({
      top: offsetTop,
      behavior: forceSmooth && !this.prefersReducedMotion
        ? 'smooth'
        : 'auto'
    });
    this.setActiveSection(sectionId);
  }

  private setActiveSection(
    sectionId: PortfolioSectionId
  ): void {
    this.activeSectionId.set(sectionId);
    this.cdr.markForCheck();
  }

  private getStickyOffsetPx(
    windowRef: Window
  ): number {
    const baseOffset = this.getLayoutHeaderHeightPx(windowRef);
    const navHeight = this.getSectionNavHeightPx();
    return Math.max(0, Math.floor(baseOffset + navHeight));
  }

  private getLayoutHeaderHeightPx(
    windowRef: Window
  ): number {
    const documentElement = this.document.documentElement;
    const layoutElement = this.document.querySelector('.public-layout');
    const tokenSource = layoutElement ?? documentElement;

    if (!tokenSource) return 0;

    const computed = windowRef.getComputedStyle(tokenSource);
    const token = computed.getPropertyValue('--bgp-public-header-height').trim();
    const fromToken = parsePixels(token);

    if (fromToken !== null) {
      return fromToken;
    }

    const fallbackHeader = this.document
      .querySelector('bgp-public-header')
      ?.getBoundingClientRect().height;

    if (typeof fallbackHeader === 'number') return fallbackHeader;

    return 78;
  }

  private getSectionNavHeightPx(): number {
    const nav = this.document.querySelector<HTMLElement>('.portfolio-section-nav');
    if (!nav) return 0;
    return nav.getBoundingClientRect().height;
  }
}
