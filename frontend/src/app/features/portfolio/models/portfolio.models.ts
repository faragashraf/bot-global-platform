import type * as PortfolioSource from '../data/portfolio.source';

export type PortfolioProfile =
  typeof PortfolioSource.profile;

export type PortfolioAboutText =
  typeof PortfolioSource.aboutText;

export type PortfolioExpertiseItem =
  (typeof PortfolioSource.expertise)[number];

export type PortfolioCaseStudy =
  (typeof PortfolioSource.caseStudies)[number];

export type PortfolioTechStackItem =
  (typeof PortfolioSource.techStack)[number];

export type PortfolioCertification =
  (typeof PortfolioSource.certifications)[number];

export type PortfolioExperience =
  typeof PortfolioSource.experience;

export type PortfolioContactLink =
  (typeof PortfolioSource.contactLinks)[number];

export interface PortfolioContent {
  readonly profile: PortfolioProfile;
  readonly aboutText: PortfolioAboutText;
  readonly expertise: readonly PortfolioExpertiseItem[];
  readonly caseStudies: readonly PortfolioCaseStudy[];
  readonly techStack: readonly PortfolioTechStackItem[];
  readonly certifications: readonly PortfolioCertification[];
  readonly experience: PortfolioExperience;
  readonly contactLinks: readonly PortfolioContactLink[];
}
