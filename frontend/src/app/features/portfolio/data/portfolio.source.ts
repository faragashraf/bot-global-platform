export interface ExpertiseItem {
  title: string;
  description: string;
  icon: string;
}

export interface CaseStudy {
  category: string;
  title: string;
  subtitle: string;
  description: string;
  visual: 'api' | 'database' | 'workflow' | 'reliability';
  visualMockup: CaseVisualMockup;
  highlights: string[];
  tags: string[];
  integrationContext: IntegrationContext[];
}

export interface CaseVisualMockup {
  eyebrow: string;
  title: string;
  metrics: CaseVisualMetric[];
  flow: string[];
  rows: CaseVisualRow[];
  tags: string[];
}

export interface CaseVisualMetric {
  label: string;
  value: string;
  caption: string;
}

export interface CaseVisualRow {
  label: string;
  value: string;
  status: string;
}

export interface IntegrationContext {
  label: string;
  logo?: string;
  logoMode?: 'icon' | 'wordmark';
}

export interface CertificationItem {
  title: string;
  issuer: string;
  date: string;
  image: string;
  category: string;
}

export interface ContactLink {
  label: string;
  value: string;
  href?: string;
}

export const profile = {
  name: 'Ashraf Farag Allah',
  role: 'Senior Backend Developer',
  headerTitle: 'Senior Backend Developer | .NET, APIs, Oracle & SQL Server',
  positioning:
    'Senior Backend Developer | .NET, APIs, Oracle & SQL Server | Enterprise & Government Integrations',
  subtitle: 'C#/.NET - REST APIs - Oracle - SQL Server - Angular - Enterprise Integrations',
  pitch:
    'I build and improve business-critical systems where reliability, performance, data accuracy, and maintainability matter.',
  location: 'Cairo, Egypt',
  linkedin: 'https://www.linkedin.com/in/ashraf-farag-allah-90860019b/',
  github: 'https://github.com/faragashraf',
  email: 'farag.ashraf@icloud.com',
  phone: '+201551111732',
  phoneHref: 'tel:+201551111732',
  whatsapp: 'https://wa.me/201551111732',
  availability:
    'Open to selected backend, .NET, enterprise integration, and technical leadership opportunities',
  image: '/assets/profile.jpg',
  cv: '/assets/Ashraf-Farag-CV.pdf',
};

export const aboutText = [
  'I am a Senior Backend / Full Stack Developer specializing in C#/.NET, REST APIs, Oracle, SQL Server, Angular, and enterprise system integrations.',
  'I focus on building and improving business-critical systems where reliability, performance, data accuracy, and maintainability matter. My work includes backend development, API design, database-driven applications, enterprise integrations, production troubleshooting, reporting workflows, and performance optimization.',
  'I combine software engineering with systems analysis, which helps me understand business requirements deeply and turn them into stable, practical, and scalable technical solutions.',
];

export const expertise: ExpertiseItem[] = [
  {
    title: 'Backend Engineering',
    description: 'C#/.NET, ASP.NET Core, REST APIs, service design',
    icon: 'server',
  },
  {
    title: 'Database Systems',
    description: 'SQL Server, Oracle, stored procedures, reporting, performance tuning',
    icon: 'database',
  },
  {
    title: 'Enterprise Integrations',
    description: 'Data exchange, validation flows, internal and external platform integration',
    icon: 'integration',
  },
  {
    title: 'Production Reliability',
    description: 'IIS, logs, scheduled jobs, troubleshooting, root-cause analysis',
    icon: 'reliability',
  },
  {
    title: 'Frontend Delivery',
    description: 'Angular, TypeScript, responsive internal tools, dynamic forms',
    icon: 'frontend',
  },
  {
    title: 'System Analysis',
    description: 'Requirements analysis, workflow mapping, documentation, business alignment',
    icon: 'analysis',
  },
];

export const caseStudies: CaseStudy[] = [
  {
    category: 'Government integration',
    title: 'Digital Egypt Integration',
    subtitle: 'Government digital services integration through Egypt Post',
    description:
      'Contributed to backend and API integration flows connecting Egypt Post operational systems with Digital Egypt service channels, focusing on reliable data exchange, request validation, traceability, queue-based processing, and production support.',
    visual: 'api',
    visualMockup: {
      eyebrow: 'Integration dashboard',
      title: 'Digital Egypt service exchange',
      metrics: [
        { label: 'API channels', value: '03', caption: 'Service lanes' },
        { label: 'Validation rules', value: '24', caption: 'Controlled checks' },
        { label: 'Trace coverage', value: '100%', caption: 'Traceability' },
      ],
      flow: ['Citizen request', 'Egypt Post', 'API gateway', 'Digital Egypt'],
      rows: [
        { label: 'Request validation', value: 'Business rules', status: 'Active' },
        { label: 'Data exchange', value: 'Oracle / SQL', status: 'Synced' },
        { label: 'Operational trace', value: 'Correlation ID', status: 'Enabled' },
      ],
      tags: ['Government APIs', 'Queue retry', 'Audit-safe'],
    },
    highlights: [
      'Backend/API integration flows',
      'Request validation and data accuracy',
      'Queue-based processing and retries',
      'Production troubleshooting and operational reliability',
    ],
    tags: ['.NET', 'C#', 'REST APIs', 'Oracle', 'SQL Server', 'Hangfire'],
    integrationContext: [
      {
        label: 'Egypt Post',
        logo: 'assets/logos/egypt-post.svg',
      },
      {
        label: 'Digital Egypt',
        logo: 'assets/logos/digital-egypt.svg',
      },
    ],
  },
  {
    category: 'Government service flows',
    title: 'Prosecution Services Integrations',
    subtitle: 'Public Prosecution, Family Prosecution, and Traffic Prosecution service flows',
    description:
      'Participated in government service integration work connecting Egypt Post internal platforms with prosecution-related service flows, including Public Prosecution, Family Prosecution, and Traffic Prosecution contexts. The work focused on backend services, data validation, operational accuracy, queue handling, reporting support, and production stability.',
    visual: 'workflow',
    visualMockup: {
      eyebrow: 'Service workflow dashboard',
      title: 'Prosecution services routing',
      metrics: [
        { label: 'Service tracks', value: '03', caption: 'Prosecution contexts' },
        { label: 'Validation rules', value: '18', caption: 'Business checks' },
        { label: 'Handoff state', value: 'Live', caption: 'Operational status' },
      ],
      flow: ['Intake', 'Validate', 'Route', 'Confirm receipt'],
      rows: [
        { label: 'Public Prosecution', value: 'Service path', status: 'Mapped' },
        { label: 'Family Prosecution', value: 'Form workflow', status: 'Validated' },
        { label: 'Traffic Prosecution', value: 'Queue handoff', status: 'Tracked' },
      ],
      tags: ['Service routing', 'Validation', 'Receipts'],
    },
    highlights: [
      'Integration with prosecution-related service flows',
      'Backend services and operational data exchange',
      'Validation, logging, and traceability',
      'Reliability support for production operations',
    ],
    tags: ['.NET', 'REST APIs', 'Oracle', 'SQL Server', 'Integration', 'Reporting'],
    integrationContext: [
      {
        label: 'Egypt Post',
        logo: 'assets/logos/egypt-post.svg',
      },
      {
        label: 'Public Prosecution',
        logo: 'assets/logos/public-prosecution.png',
      },
      {
        label: 'Family Prosecution',
      },
      {
        label: 'Traffic Prosecution',
      },
    ],
  },
  {
    category: 'Background operations',
    title: 'Background Jobs & Hangfire Operations',
    subtitle: 'Scheduled processing, queues, retries, and operational automation',
    description:
      'Worked on Hangfire-based background processing for scheduled jobs, recurring tasks, queue handling, integration workflows, automated dispatching, retries, and operational monitoring. The focus was on improving reliability, visibility, and maintainability for backend processes that run outside the normal request lifecycle.',
    visual: 'reliability',
    visualMockup: {
      eyebrow: 'Operations dashboard',
      title: 'Hangfire background operations',
      metrics: [
        { label: 'Recurring schedules', value: '18', caption: 'Schedule set' },
        { label: 'Worker queues', value: '06', caption: 'Active lanes' },
        { label: 'Critical alerts', value: '0', caption: 'Healthy state' },
      ],
      flow: ['Schedule', 'Dispatch', 'Retry policy', 'Operations log'],
      rows: [
        { label: 'Mail queue worker', value: 'Running', status: 'Healthy' },
        { label: 'Integration jobs', value: 'Monitored', status: 'Stable' },
        { label: 'Retry handling', value: 'Policy based', status: 'Controlled' },
      ],
      tags: ['Hangfire', 'Retry policy', 'Monitoring'],
    },
    highlights: [
      'Recurring and scheduled jobs',
      'Queue processing and retries',
      'Operational logs and monitoring',
      'Background automation for integration flows',
    ],
    tags: ['Hangfire', '.NET', 'C#', 'SQL Server', 'Oracle', 'Background Jobs'],
    integrationContext: [
      {
        label: 'Hangfire',
        logo: 'assets/logos/hangfire.svg',
        logoMode: 'wordmark',
      },
      {
        label: '.NET',
        logo: 'assets/logos/dotnet.svg',
        logoMode: 'wordmark',
      },
    ],
  },
  {
    category: 'Internal engineering tools',
    title: 'Internal NuGet Package Service',
    subtitle: 'Reusable internal backend package for shared services and development standards',
    description:
      'Built an internal reusable NuGet package/service layer to standardize shared backend capabilities across projects, reduce duplicated code, improve maintainability, and accelerate implementation of common services, helpers, integration utilities, logging patterns, and reusable development components.',
    visual: 'database',
    visualMockup: {
      eyebrow: 'Engineering platform',
      title: 'Internal NuGet package service',
      metrics: [
        { label: 'Shared modules', value: '06', caption: 'Reusable services' },
        { label: 'Release model', value: 'SemVer', caption: 'Version control' },
        { label: 'Boilerplate', value: '-35%', caption: 'Reusable baseline' },
      ],
      flow: ['Core library', 'Integration helpers', 'Package release', 'Project adoption'],
      rows: [
        { label: 'Common services', value: 'Reusable layer', status: 'Packaged' },
        { label: 'Integration helpers', value: 'Standardized', status: 'Published' },
        { label: 'Developer onboarding', value: 'Faster setup', status: 'Documented' },
      ],
      tags: ['NuGet', '.NET libraries', 'Standards'],
    },
    highlights: [
      'Internal reusable package/service layer',
      'Reduced duplicated backend logic',
      'Shared helpers and integration utilities',
      'Improved maintainability across projects',
    ],
    tags: ['.NET', 'C#', 'NuGet', 'Reusable Libraries', 'Clean Code', 'Internal Tools'],
    integrationContext: [
      {
        label: 'NuGet',
        logo: 'assets/logos/nuget.svg',
      },
      {
        label: '.NET',
        logo: 'assets/logos/dotnet.svg',
        logoMode: 'wordmark',
      },
    ],
  },
  {
    category: 'Reporting automation',
    title: 'Auto Mail Queue & Excel Reporting',
    subtitle: 'Automated email queue with scheduled Excel report generation',
    description:
      'Designed and implemented an automated mail queue service that generates Excel reports and sends scheduled operational reports to stakeholders. The solution helped reduce manual reporting effort, improve delivery consistency, and support data-driven operational follow-up.',
    visual: 'api',
    visualMockup: {
      eyebrow: 'Reporting automation',
      title: 'Excel reporting and mail queue',
      metrics: [
        { label: 'Scheduled reports', value: '24', caption: 'Recurring setup' },
        { label: 'Workbook output', value: 'XLSX', caption: 'Excel format' },
        { label: 'Dispatch mode', value: 'Auto', caption: 'Mail queue' },
      ],
      flow: ['Query data', 'Generate workbook', 'Queue email', 'Deliver'],
      rows: [
        { label: 'Daily operations report', value: '07:30', status: 'Scheduled' },
        { label: 'Excel workbook', value: 'Formatted', status: 'Ready' },
        { label: 'Stakeholder email', value: 'Queued', status: 'Delivered' },
      ],
      tags: ['Excel', 'Scheduled jobs', 'Mail queue'],
    },
    highlights: [
      'Automated mail queue',
      'Scheduled Excel report generation',
      'Operational reporting automation',
      'Reduced manual follow-up effort',
    ],
    tags: ['.NET', 'C#', 'Mail Queue', 'Excel Reports', 'SQL Server', 'Oracle', 'Hangfire'],
    integrationContext: [
      {
        label: '.NET',
        logo: 'assets/logos/dotnet.svg',
        logoMode: 'wordmark',
      },
      {
        label: 'Excel',
        logo: 'assets/logos/excel.svg',
      },
      {
        label: 'Hangfire',
        logo: 'assets/logos/hangfire.svg',
        logoMode: 'wordmark',
      },
    ],
  },
];

export const techStack = [
  'C#',
  '.NET',
  'ASP.NET Core',
  'REST APIs',
  'SQL Server',
  'Oracle',
  'Angular',
  'TypeScript',
  'HTML',
  'CSS',
  'IIS',
  'Git',
  'Postman',
  'Performance Optimization',
  'System Analysis',
];

export const certifications: CertificationItem[] = [
  {
    title: 'Project Management Professional (PMP)',
    issuer: 'ACICIT / Egypt Post',
    date: 'February 2023',
    image: '/assets/certificates/certificate-01-project-management.jpg',
    category: 'Project Management',
  },
  {
    title: 'Data Visualisation: Empowering Business with Effective Insights',
    issuer: 'Forage / Tata',
    date: 'March 2023',
    image: '/assets/certificates/certificate-03-data-visualization.jpg',
    category: 'Data & Analytics',
  },
  {
    title: 'Digital Transformation',
    issuer: 'Egyptian Banking Institute / Central Bank of Egypt',
    date: 'June 2021',
    image: '/assets/certificates/certificate-02-digital-transformation.jpg',
    category: 'Digital Transformation',
  },
  {
    title: 'Advanced Excel',
    issuer: 'ACICT',
    date: 'April 2018',
    image: '/assets/certificates/certificate-advanced-excel.jpg',
    category: 'Data & Productivity',
  },
  {
    title: 'Financial Inclusion',
    issuer: 'Egyptian Banking Institute / Central Bank of Egypt',
    date: 'August 2021',
    image: '/assets/certificates/certificate-financial-inclusion.jpg',
    category: 'Financial Services',
  },
  {
    title: 'Effective Communication',
    issuer: 'ACICT',
    date: 'May 2017',
    image: '/assets/certificates/certificate-effective-communication.jpg',
    category: 'Communication',
  },
  {
    title: 'Positive Behaviors',
    issuer: 'Harvest Training and Education Center',
    date: 'January 2017',
    image: '/assets/certificates/certificate-positive-behaviors.jpg',
    category: 'Soft Skills',
  },
  {
    title: 'Integrated ISO 9001, ISO 14001 & OHSAS 18001 Internal Auditing',
    issuer: 'EMSI',
    date: 'April 2010',
    image: '/assets/certificates/certificate-04-quality-iso-internal-auditing.jpg',
    category: 'Quality & Auditing',
  },
  {
    title: 'Integrated System Management Awareness',
    issuer: 'EMSI',
    date: 'January 2010',
    image: '/assets/certificates/certificate-04-quality-iso-awareness.jpg',
    category: 'Quality & Management',
  },
];

export const experience = {
  role: 'Senior Backend Developer / Technical Lead',
  company: 'Egypt Post',
  bullets: [
    'Design, develop, and maintain enterprise-grade backend systems using .NET, SQL Server, and Oracle.',
    'Build and support REST APIs and integrations between internal platforms and enterprise/government systems.',
    'Analyze business requirements and convert them into reliable, maintainable technical solutions.',
    'Work on database-driven workflows, reporting, stored procedures, and data validation processes.',
    'Troubleshoot production issues across APIs, IIS-hosted services, scheduled jobs, and database layers.',
    'Improve system reliability, performance, and operational data accuracy.',
    'Collaborate with business, support, and technical teams to deliver stable internal systems.',
  ],
};

export const contactLinks: ContactLink[] = [
  {
    label: 'Email',
    value: profile.email,
    href: `mailto:${profile.email}`,
  },
  {
    label: 'Phone',
    value: profile.phone,
    href: profile.phoneHref,
  },
  {
    label: 'WhatsApp',
    value: 'Message on WhatsApp',
    href: profile.whatsapp,
  },
  {
    label: 'LinkedIn',
    value: 'ashraf-farag-allah-90860019b',
    href: profile.linkedin,
  },
  {
    label: 'GitHub',
    value: 'faragashraf',
    href: profile.github,
  },
  {
    label: 'Location',
    value: profile.location,
  },
];
