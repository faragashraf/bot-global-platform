import { CatalogProduct } from '../models/catalog.model';

export const CATALOG_PRODUCTS: readonly CatalogProduct[] = [
  {
    id: 'sentricam',
    slug: 'sentricam',
    category: 'app',
    featured: true,
    name: {
      en: 'SentriCam',
      ar: 'SentriCam'
    },
    shortDescription: {
      en: 'An existing BOT GLOBAL product with public catalog details in preparation.',
      ar: 'منتج قائم من BOT GLOBAL، ويجري حاليًا إعداد تفاصيله للنشر في الكتالوج العام.'
    },
    description: {
      en: 'SentriCam is identified in the BOT GLOBAL platform documentation as an existing product. Verified public feature, platform, media, availability, and support details have not yet been published, so this entry intentionally makes no additional product claims.',
      ar: 'تُعرّف وثائق منصة BOT GLOBAL منتج SentriCam باعتباره منتجًا قائمًا. لم تُنشر بعد تفاصيل موثقة للعامة حول الميزات أو المنصات أو الوسائط أو الإتاحة أو الدعم؛ لذلك لا يتضمن هذا السجل أي ادعاءات إضافية عن المنتج.'
    },
    status: {
      en: 'Details pending',
      ar: 'التفاصيل قيد الإعداد'
    },
    platforms: [],
    technologies: [],
    screenshots: [],
    links: []
  }
] as const;
