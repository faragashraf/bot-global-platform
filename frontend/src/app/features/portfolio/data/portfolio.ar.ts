import {
  caseStudies as caseStudiesEn,
  certifications as certificationsEn,
  contactLinks as contactLinksEn,
  experience as experienceEn,
  expertise as expertiseEn,
  profile as profileEn,
  techStack as techStackEn
} from './portfolio.source';
import type {
  CaseStudy,
  CertificationItem,
  ContactLink,
  ExpertiseItem
} from './portfolio.source';

export const profile = {
  ...profileEn,
  role: 'مطور أول للأنظمة الخلفية',
  headerTitle:
    'مطور أول للأنظمة الخلفية | .NET وواجهات API وOracle وSQL Server',
  positioning:
    'مطور أول للأنظمة الخلفية | .NET وواجهات API وOracle وSQL Server | تكامل الأنظمة المؤسسية والحكومية',
  subtitle:
    'C#/.NET - REST APIs - Oracle - SQL Server - Angular - تكامل الأنظمة المؤسسية',
  pitch:
    'أبني الأنظمة الحيوية للأعمال وأطوّرها، مع التركيز على الموثوقية والأداء ودقة البيانات وسهولة الصيانة.',
  location: 'القاهرة، مصر',
  availability:
    'متاح لفرص مختارة في تطوير الأنظمة الخلفية وتقنيات .NET والتكامل المؤسسي والقيادة التقنية'
} satisfies typeof profileEn;

export const aboutText = [
  'أنا مطور أول للأنظمة الخلفية ومطور Full Stack، متخصص في C#/.NET وREST APIs وOracle وSQL Server وAngular وتكامل الأنظمة المؤسسية.',
  'أركز على بناء الأنظمة الحيوية للأعمال وتحسينها، حيث تمثل الموثوقية والأداء ودقة البيانات وسهولة الصيانة عوامل أساسية. وتشمل خبرتي تطوير الأنظمة الخلفية، وتصميم واجهات API، والتطبيقات المعتمدة على قواعد البيانات، والتكاملات المؤسسية، ومعالجة مشكلات بيئات الإنتاج، وأتمتة التقارير، وتحسين الأداء.',
  'أجمع بين هندسة البرمجيات وتحليل النظم، مما يساعدني على فهم متطلبات الأعمال بعمق وتحويلها إلى حلول تقنية مستقرة وعملية وقابلة للتوسع.'
];

const expertiseAr = [
  {
    title: 'هندسة الأنظمة الخلفية',
    description:
      'تطوير الخدمات باستخدام C#/.NET وASP.NET Core، وتصميم REST APIs وبنية الخدمات.'
  },
  {
    title: 'أنظمة قواعد البيانات',
    description:
      'SQL Server وOracle والإجراءات المخزنة والتقارير وضبط الأداء.'
  },
  {
    title: 'تكامل الأنظمة المؤسسية',
    description:
      'تبادل البيانات ومسارات التحقق والتكامل بين المنصات الداخلية والخارجية.'
  },
  {
    title: 'موثوقية بيئات الإنتاج',
    description:
      'IIS والسجلات والمهام المجدولة وتشخيص الأعطال وتحليل الأسباب الجذرية.'
  },
  {
    title: 'تطوير الواجهات الأمامية',
    description:
      'Angular وTypeScript وأدوات داخلية متجاوبة ونماذج ديناميكية.'
  },
  {
    title: 'تحليل النظم',
    description:
      'تحليل المتطلبات ورسم مسارات العمل والتوثيق ومواءمة الحلول مع احتياجات الأعمال.'
  }
] satisfies ReadonlyArray<Pick<ExpertiseItem, 'title' | 'description'>>;

if (expertiseAr.length !== expertiseEn.length) {
  throw new Error(
    `Arabic expertise parity failed: expected ${expertiseEn.length}, received ${expertiseAr.length}.`
  );
}

export const expertise: ExpertiseItem[] = expertiseEn.map((item, index) => ({
  ...item,
  ...expertiseAr[index]
}));

const expectedCaseStudyTitles = [
  'Digital Egypt Integration',
  'Prosecution Services Integrations',
  'Background Jobs & Hangfire Operations',
  'Internal NuGet Package Service',
  'Auto Mail Queue & Excel Reporting'
] as const;

type EnglishCaseStudyTitle = (typeof expectedCaseStudyTitles)[number];
type CaseStudyLocalization = Pick<
  CaseStudy,
  | 'category'
  | 'title'
  | 'subtitle'
  | 'description'
  | 'highlights'
  | 'tags'
  | 'visualMockup'
  | 'integrationContext'
>;

const caseStudyLocalizations = {
  'Digital Egypt Integration': {
    category: 'تكامل حكومي',
    title: 'تكامل خدمات مصر الرقمية',
    subtitle: 'تكامل الخدمات الحكومية الرقمية عبر البريد المصري',
    description:
      'ساهمت في تطوير مسارات التكامل الخلفية وواجهات API التي تربط الأنظمة التشغيلية للبريد المصري بقنوات خدمات مصر الرقمية، مع التركيز على موثوقية تبادل البيانات والتحقق من الطلبات وقابلية التتبع والمعالجة عبر قوائم الانتظار ودعم بيئات الإنتاج.',
    visualMockup: {
      eyebrow: 'لوحة متابعة التكامل',
      title: 'تبادل خدمات مصر الرقمية',
      metrics: [
        { label: 'قنوات API', value: '03', caption: 'مسارات الخدمة' },
        { label: 'قواعد التحقق', value: '24', caption: 'ضوابط تحقق محكمة' },
        { label: 'تغطية التتبع', value: '100%', caption: 'قابلية تتبع كاملة' }
      ],
      flow: ['طلب المواطن', 'البريد المصري', 'بوابة API', 'مصر الرقمية'],
      rows: [
        { label: 'التحقق من الطلب', value: 'قواعد الأعمال', status: 'فعّال' },
        { label: 'تبادل البيانات', value: 'Oracle / SQL', status: 'متزامن' },
        { label: 'التتبع التشغيلي', value: 'Correlation ID', status: 'مفعّل' }
      ],
      tags: ['واجهات حكومية', 'إعادة المحاولة عبر قائمة الانتظار', 'تدقيق آمن']
    },
    highlights: [
      'تطوير مسارات التكامل الخلفية وواجهات API',
      'التحقق من الطلبات وضمان دقة البيانات',
      'المعالجة عبر قوائم الانتظار وسياسات إعادة المحاولة',
      'تشخيص مشكلات الإنتاج وتعزيز الموثوقية التشغيلية'
    ],
    tags: ['.NET', 'C#', 'REST APIs', 'Oracle', 'SQL Server', 'Hangfire'],
    integrationContext: [
      { label: 'البريد المصري', logo: 'assets/logos/egypt-post.svg' },
      { label: 'مصر الرقمية', logo: 'assets/logos/digital-egypt.svg' }
    ]
  },
  'Prosecution Services Integrations': {
    category: 'مسارات الخدمات الحكومية',
    title: 'تكامل خدمات النيابات',
    subtitle: 'مسارات خدمات النيابة العامة ونيابة الأسرة ونيابة المرور',
    description:
      'شاركت في تكامل الخدمات الحكومية بين المنصات الداخلية للبريد المصري ومسارات الخدمات المرتبطة بالنيابة العامة ونيابة الأسرة ونيابة المرور. وتركز العمل على الخدمات الخلفية والتحقق من البيانات والدقة التشغيلية وإدارة قوائم الانتظار ودعم التقارير واستقرار بيئات الإنتاج.',
    visualMockup: {
      eyebrow: 'لوحة متابعة مسارات الخدمة',
      title: 'توجيه خدمات النيابات',
      metrics: [
        { label: 'مسارات الخدمة', value: '03', caption: 'مسارات النيابات' },
        { label: 'قواعد التحقق', value: '18', caption: 'ضوابط الأعمال' },
        { label: 'حالة التسليم', value: 'Live', caption: 'الحالة التشغيلية' }
      ],
      flow: ['استقبال الطلب', 'التحقق', 'التوجيه', 'تأكيد الاستلام'],
      rows: [
        { label: 'النيابة العامة', value: 'مسار الخدمة', status: 'محدد المسار' },
        { label: 'نيابة الأسرة', value: 'مسار النموذج', status: 'تم التحقق' },
        { label: 'نيابة المرور', value: 'تسليم عبر قائمة الانتظار', status: 'قيد التتبع' }
      ],
      tags: ['توجيه الخدمات', 'التحقق', 'إيصالات الاستلام']
    },
    highlights: [
      'التكامل مع مسارات الخدمات المرتبطة بالنيابات',
      'الخدمات الخلفية وتبادل البيانات التشغيلية',
      'التحقق والتسجيل وقابلية التتبع',
      'دعم موثوقية العمليات في بيئات الإنتاج'
    ],
    tags: ['.NET', 'REST APIs', 'Oracle', 'SQL Server', 'تكامل', 'تقارير'],
    integrationContext: [
      { label: 'البريد المصري', logo: 'assets/logos/egypt-post.svg' },
      {
        label: 'النيابة العامة',
        logo: 'assets/logos/public-prosecution.png'
      },
      { label: 'نيابة الأسرة' },
      { label: 'نيابة المرور' }
    ]
  },
  'Background Jobs & Hangfire Operations': {
    category: 'العمليات الخلفية',
    title: 'تشغيل المهام الخلفية باستخدام Hangfire',
    subtitle: 'المعالجة المجدولة وقوائم الانتظار وإعادة المحاولة والأتمتة التشغيلية',
    description:
      'عملت على المعالجة الخلفية باستخدام Hangfire للمهام المجدولة والمتكررة، وإدارة قوائم الانتظار ومسارات التكامل والإرسال الآلي وسياسات إعادة المحاولة والمراقبة التشغيلية. وكان التركيز على رفع موثوقية العمليات التي تعمل خارج دورة الطلب المعتادة وتحسين وضوحها وسهولة صيانتها.',
    visualMockup: {
      eyebrow: 'لوحة متابعة العمليات',
      title: 'تشغيل المهام الخلفية عبر Hangfire',
      metrics: [
        { label: 'الجداول المتكررة', value: '18', caption: 'إعدادات الجدولة' },
        { label: 'طوابير التنفيذ', value: '06', caption: 'مسارات معالجة نشطة' },
        { label: 'التنبيهات الحرجة', value: '0', caption: 'حالة سليمة' }
      ],
      flow: ['الجدولة', 'الإرسال', 'سياسة إعادة المحاولة', 'سجل العمليات'],
      rows: [
        { label: 'عامل قائمة البريد', value: 'قيد التشغيل', status: 'سليم' },
        { label: 'مهام التكامل', value: 'تحت المراقبة', status: 'مستقر' },
        { label: 'معالجة إعادة المحاولة', value: 'وفق سياسة', status: 'محكوم' }
      ],
      tags: ['Hangfire', 'سياسة إعادة المحاولة', 'المراقبة']
    },
    highlights: [
      'المهام المتكررة والمجدولة',
      'معالجة قوائم الانتظار وإعادة المحاولة',
      'السجلات التشغيلية والمراقبة',
      'أتمتة العمليات الخلفية لمسارات التكامل'
    ],
    tags: ['Hangfire', '.NET', 'C#', 'SQL Server', 'Oracle', 'مهام خلفية'],
    integrationContext: [
      {
        label: 'Hangfire',
        logo: 'assets/logos/hangfire.svg',
        logoMode: 'wordmark'
      },
      {
        label: '.NET',
        logo: 'assets/logos/dotnet.svg',
        logoMode: 'wordmark'
      }
    ]
  },
  'Internal NuGet Package Service': {
    category: 'أدوات هندسية داخلية',
    title: 'خدمة حزم NuGet الداخلية',
    subtitle: 'حزمة خلفية داخلية قابلة لإعادة الاستخدام للخدمات المشتركة ومعايير التطوير',
    description:
      'أنشأت حزمة NuGet داخلية قابلة لإعادة الاستخدام وطبقة خدمات موحدة لتوحيد القدرات الخلفية المشتركة بين المشروعات، وتقليل تكرار الشفرة، وتحسين قابلية الصيانة، وتسريع تنفيذ الخدمات والأدوات المساعدة وأدوات التكامل وأنماط التسجيل ومكونات التطوير المشتركة.',
    visualMockup: {
      eyebrow: 'منصة هندسية',
      title: 'خدمة حزم NuGet الداخلية',
      metrics: [
        { label: 'الوحدات المشتركة', value: '06', caption: 'خدمات قابلة لإعادة الاستخدام' },
        { label: 'نموذج الإصدار', value: 'SemVer', caption: 'إدارة الإصدارات' },
        { label: 'الشفرة النمطية المتكررة', value: '-35%', caption: 'خفض التكرار' }
      ],
      flow: ['المكتبة الأساسية', 'أدوات التكامل', 'إصدار الحزمة', 'اعتمادها في المشروعات'],
      rows: [
        { label: 'الخدمات المشتركة', value: 'طبقة قابلة لإعادة الاستخدام', status: 'جاهزة كحزمة' },
        { label: 'أدوات التكامل', value: 'موحدة', status: 'منشورة' },
        { label: 'تهيئة المطورين', value: 'إعداد أسرع', status: 'موثقة' }
      ],
      tags: ['NuGet', 'مكتبات .NET', 'المعايير']
    },
    highlights: [
      'حزمة داخلية وطبقة خدمات قابلة لإعادة الاستخدام',
      'تقليل تكرار منطق الأنظمة الخلفية',
      'أدوات مساعدة وقدرات تكامل مشتركة',
      'تحسين قابلية الصيانة عبر المشروعات'
    ],
    tags: ['.NET', 'C#', 'NuGet', 'مكتبات قابلة لإعادة الاستخدام', 'شفرة نظيفة', 'أدوات داخلية'],
    integrationContext: [
      { label: 'NuGet', logo: 'assets/logos/nuget.svg' },
      {
        label: '.NET',
        logo: 'assets/logos/dotnet.svg',
        logoMode: 'wordmark'
      }
    ]
  },
  'Auto Mail Queue & Excel Reporting': {
    category: 'أتمتة التقارير',
    title: 'قائمة البريد الآلية وتقارير Excel',
    subtitle: 'قائمة بريد آلية مع إنشاء تقارير Excel مجدولة',
    description:
      'صممت ونفذت خدمة آلية لقائمة البريد تُنشئ تقارير Excel وترسل التقارير التشغيلية المجدولة إلى أصحاب المصلحة. ساعد الحل على تقليل الجهد اليدوي لإعداد التقارير، وتحسين انتظام التسليم، ودعم المتابعة التشغيلية القائمة على البيانات.',
    visualMockup: {
      eyebrow: 'أتمتة التقارير',
      title: 'تقارير Excel وقائمة البريد',
      metrics: [
        { label: 'التقارير المجدولة', value: '24', caption: 'إعدادات متكررة' },
        { label: 'صيغة المصنف', value: 'XLSX', caption: 'تنسيق Excel' },
        { label: 'نمط الإرسال', value: 'Auto', caption: 'قائمة البريد' }
      ],
      flow: ['استعلام البيانات', 'إنشاء المصنف', 'إضافة البريد إلى القائمة', 'التسليم'],
      rows: [
        { label: 'تقرير العمليات اليومي', value: '07:30', status: 'مجدول' },
        { label: 'مصنف Excel', value: 'منسق', status: 'جاهز' },
        { label: 'بريد أصحاب المصلحة', value: 'في قائمة الانتظار', status: 'تم التسليم' }
      ],
      tags: ['Excel', 'مهام مجدولة', 'قائمة البريد']
    },
    highlights: [
      'قائمة بريد آلية',
      'إنشاء تقارير Excel مجدولة',
      'أتمتة التقارير التشغيلية',
      'تقليل جهد المتابعة اليدوية'
    ],
    tags: ['.NET', 'C#', 'قائمة البريد', 'تقارير Excel', 'SQL Server', 'Oracle', 'Hangfire'],
    integrationContext: [
      {
        label: '.NET',
        logo: 'assets/logos/dotnet.svg',
        logoMode: 'wordmark'
      },
      { label: 'Excel', logo: 'assets/logos/excel.svg' },
      {
        label: 'Hangfire',
        logo: 'assets/logos/hangfire.svg',
        logoMode: 'wordmark'
      }
    ]
  }
} satisfies Record<EnglishCaseStudyTitle, CaseStudyLocalization>;

const englishCaseStudyTitles = caseStudiesEn.map((study) => study.title);
const duplicateEnglishCaseStudyTitles = englishCaseStudyTitles.filter(
  (title, index) => englishCaseStudyTitles.indexOf(title) !== index
);
const missingArabicCaseStudies = englishCaseStudyTitles.filter(
  (title) => !Object.prototype.hasOwnProperty.call(caseStudyLocalizations, title)
);
const extraArabicCaseStudies = Object.keys(caseStudyLocalizations).filter(
  (title) => !englishCaseStudyTitles.includes(title)
);

if (
  duplicateEnglishCaseStudyTitles.length > 0 ||
  missingArabicCaseStudies.length > 0 ||
  extraArabicCaseStudies.length > 0 ||
  caseStudiesEn.length !== expectedCaseStudyTitles.length
) {
  throw new Error(
    [
      'Arabic case-study parity failed.',
      `English count: ${caseStudiesEn.length}.`,
      `Arabic count: ${Object.keys(caseStudyLocalizations).length}.`,
      `Duplicate English titles: ${duplicateEnglishCaseStudyTitles.join(', ') || 'none'}.`,
      `Missing Arabic titles: ${missingArabicCaseStudies.join(', ') || 'none'}.`,
      `Extra Arabic titles: ${extraArabicCaseStudies.join(', ') || 'none'}.`
    ].join(' ')
  );
}

export const caseStudies: CaseStudy[] = caseStudiesEn.map((study) => ({
  ...study,
  ...caseStudyLocalizations[study.title as EnglishCaseStudyTitle]
}));

const techStackTranslations: Record<string, string> = {
  'Performance Optimization': 'تحسين الأداء',
  'System Analysis': 'تحليل النظم'
};

export const techStack = techStackEn.map(
  (technology) => techStackTranslations[technology] ?? technology
);

export const experience = {
  ...experienceEn,
  role: 'مطور أول للأنظمة الخلفية / قائد تقني',
  company: 'البريد المصري',
  bullets: [
    'تصميم أنظمة خلفية مؤسسية وتطويرها وصيانتها باستخدام .NET وSQL Server وOracle.',
    'بناء REST APIs ودعم التكاملات بين المنصات الداخلية والأنظمة المؤسسية والحكومية.',
    'تحليل متطلبات الأعمال وتحويلها إلى حلول تقنية موثوقة وقابلة للصيانة.',
    'تطوير مسارات العمل المعتمدة على قواعد البيانات والتقارير والإجراءات المخزنة وعمليات التحقق من البيانات.',
    'تشخيص مشكلات الإنتاج عبر واجهات API والخدمات المستضافة على IIS والمهام المجدولة وطبقات قواعد البيانات.',
    'تحسين موثوقية الأنظمة وأدائها ودقة البيانات التشغيلية.',
    'التعاون مع فرق الأعمال والدعم والفرق التقنية لتسليم أنظمة داخلية مستقرة.'
  ]
} satisfies typeof experienceEn;

const certificationCategoryTranslations: Record<string, string> = {
  'Project Management': 'إدارة المشروعات',
  'Data & Analytics': 'البيانات والتحليلات',
  'Digital Transformation': 'التحول الرقمي',
  'Data & Productivity': 'البيانات والإنتاجية',
  'Financial Services': 'الخدمات المالية',
  Communication: 'التواصل',
  'Soft Skills': 'المهارات الشخصية',
  'Quality & Auditing': 'الجودة والتدقيق',
  'Quality & Management': 'الجودة والإدارة'
};

const monthTranslations: Record<string, string> = {
  January: 'يناير',
  February: 'فبراير',
  March: 'مارس',
  April: 'أبريل',
  May: 'مايو',
  June: 'يونيو',
  July: 'يوليو',
  August: 'أغسطس',
  September: 'سبتمبر',
  October: 'أكتوبر',
  November: 'نوفمبر',
  December: 'ديسمبر'
};

function localizeCertificationCategory(category: string): string {
  const localized = certificationCategoryTranslations[category];

  if (!localized) {
    throw new Error(`Missing Arabic certification category: ${category}.`);
  }

  return localized;
}

function localizeCertificationDate(date: string): string {
  const [month, ...rest] = date.split(' ');
  const localizedMonth = monthTranslations[month];

  if (!localizedMonth || rest.length === 0) {
    throw new Error(`Unsupported certification date for Arabic: ${date}.`);
  }

  return `${localizedMonth} ${rest.join(' ')}`;
}

export const certifications: CertificationItem[] = certificationsEn.map(
  (certificate) => ({
    ...certificate,
    date: localizeCertificationDate(certificate.date),
    category: localizeCertificationCategory(certificate.category)
  })
);

const contactLabelTranslations: Record<string, string> = {
  Email: 'البريد الإلكتروني',
  Phone: 'الهاتف',
  WhatsApp: 'واتساب',
  LinkedIn: 'LinkedIn',
  GitHub: 'GitHub',
  Location: 'الموقع'
};

function localizeContactLabel(label: string): string {
  const localized = contactLabelTranslations[label];

  if (!localized) {
    throw new Error(`Missing Arabic contact label: ${label}.`);
  }

  return localized;
}

export const contactLinks: ContactLink[] = contactLinksEn.map((link) => ({
  ...link,
  label: localizeContactLabel(link.label),
  value:
    link.label === 'WhatsApp'
      ? 'مراسلة عبر واتساب'
      : link.label === 'Location'
        ? profile.location
        : link.value
}));
