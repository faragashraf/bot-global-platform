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

const sourcePortfolio = {
  profile,
  aboutText,
  expertise,
  caseStudies,
  techStack,
  certifications,
  experience,
  contactLinks
} satisfies PortfolioContent;

export const PORTFOLIO_DATA: PortfolioContent =
  normalizePortfolioAssetPaths(sourcePortfolio);

export { PORTFOLIO_ASSET_ROOT };
