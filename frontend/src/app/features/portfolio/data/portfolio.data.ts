import * as ar from './portfolio.ar';

import {
  aboutText,
  caseStudies,
  certifications,
  contactLinks,
  experience,
  expertise,
  profile,
  techStack
} from './portfolio.source';

import type {
  PortfolioContent
} from '../models/portfolio.models';

import {
  normalizePortfolioAssetPaths,
  PORTFOLIO_ASSET_ROOT
} from './portfolio-asset-paths';

const en = {
  profile,
  aboutText,
  expertise,
  caseStudies,
  techStack,
  certifications,
  experience,
  contactLinks
} satisfies PortfolioContent;

const arabic = {
  profile: ar.profile,
  aboutText: ar.aboutText,
  expertise: ar.expertise,
  caseStudies: ar.caseStudies,
  techStack: ar.techStack,
  certifications: ar.certifications,
  experience: ar.experience,
  contactLinks: ar.contactLinks
} satisfies PortfolioContent;

const EN = normalizePortfolioAssetPaths(en);
const AR = normalizePortfolioAssetPaths(arabic);

export function getPortfolioData(
  language: string
): PortfolioContent {
  return language === 'ar' ? AR : EN;
}

export const PORTFOLIO_DATA = EN;

export { PORTFOLIO_ASSET_ROOT };
