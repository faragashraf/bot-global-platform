import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';

import { AdminCatalogService } from '../../data-access/admin-catalog.service';
import type {
  AdminCatalogProductDetail,
  AdminCatalogProductWriteRequest
} from '../../models/admin-catalog.model';
import { CatalogProductFormPageComponent } from './catalog-product-form-page.component';

const PRODUCT: AdminCatalogProductDetail = {
  id: 'draft-id',
  slug: 'draft-product',
  category: 'app',
  publicationStatus: 'Draft',
  featured: false,
  sortOrder: 4,
  publishedAtUtc: null,
  nameEn: 'Draft product',
  nameAr: 'منتج مسودة',
  localizations: {
    en: {
      name: 'Draft product',
      shortDescription: 'English short',
      description: 'English description',
      displayStatus: 'Draft',
      platforms: ['Web'],
      technologies: ['Angular']
    },
    ar: {
      name: 'منتج مسودة',
      shortDescription: 'وصف مختصر',
      description: 'الوصف العربي',
      displayStatus: 'مسودة',
      platforms: ['الويب'],
      technologies: ['أنجولار']
    }
  },
  links: []
};

interface CatalogServiceStub {
  getProduct: ReturnType<typeof vi.fn>;
  createProduct: ReturnType<typeof vi.fn>;
  updateProduct: ReturnType<typeof vi.fn>;
}

function configure(
  id: string | null,
  getProduct: Observable<AdminCatalogProductDetail> = of(PRODUCT)
) {
  const catalog: CatalogServiceStub = {
    getProduct: vi.fn(() => getProduct),
    createProduct: vi.fn(() => of(PRODUCT)),
    updateProduct: vi.fn(() => of(PRODUCT))
  };
  const router = { navigate: vi.fn(() => Promise.resolve(true)) };

  TestBed.configureTestingModule({
    imports: [CatalogProductFormPageComponent, TranslateModule.forRoot()],
    providers: [
      { provide: AdminCatalogService, useValue: catalog },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            paramMap: convertToParamMap(id ? { id } : {})
          }
        }
      },
      { provide: Router, useValue: router }
    ]
  });

  const fixture = TestBed.createComponent(CatalogProductFormPageComponent);
  fixture.detectChanges();
  return { component: fixture.componentInstance, catalog, router };
}

describe('CatalogProductFormPageComponent', () => {
  it('maps create form values to one draft request shape', () => {
    const { component, catalog, router } = configure(null);
    component.form.patchValue({
      slug: 'new-draft',
      category: 'game',
      sortOrder: 3,
      en: {
        name: ' English name ',
        shortDescription: ' English short ',
        description: ' English description ',
        platforms: 'Web\nWindows',
        technologies: 'Angular, TypeScript'
      },
      ar: {
        name: ' اسم عربي ',
        shortDescription: ' وصف مختصر ',
        description: ' الوصف العربي ',
        platforms: 'الويب',
        technologies: 'أنجولار'
      }
    });
    component.submit();

    const request = catalog.createProduct.mock.calls[0][0] as AdminCatalogProductWriteRequest;
    expect(request.slug).toBe('new-draft');
    expect(request.featured).toBe(false);
    expect(request.localizations.en.platforms).toEqual(['Web', 'Windows']);
    expect(request.localizations.en.technologies).toEqual(['Angular', 'TypeScript']);
    expect(request).not.toHaveProperty('publicationStatus');
    expect(router.navigate).toHaveBeenCalledWith(['/admin/catalog'], {
      queryParams: { saved: 'created' }
    });
  });

  it('loads edit mode before submitting the shared form', () => {
    const product = new Subject<AdminCatalogProductDetail>();
    const { component, catalog } = configure('draft-id', product);
    expect(component.loading()).toBe(true);

    product.next(PRODUCT);
    product.complete();

    expect(component.loading()).toBe(false);
    expect(component.editMode).toBe(true);
    expect(component.form.controls.slug.value).toBe('draft-product');
    component.submit();
    expect(catalog.updateProduct).toHaveBeenCalledWith(
      'draft-id',
      expect.objectContaining({ slug: 'draft-product', featured: false })
    );
  });

  it('maps save conflicts to a safe localized error key', () => {
    const { component, catalog } = configure(null);
    catalog.createProduct.mockReturnValue(throwError(() =>
      new HttpErrorResponse({ status: 409 })));
    component.form.patchValue({
      slug: 'conflict-draft',
      en: {
        name: 'English',
        shortDescription: 'English short',
        description: 'English description'
      },
      ar: {
        name: 'العربية',
        shortDescription: 'وصف مختصر',
        description: 'الوصف العربي'
      }
    });

    component.submit();

    expect(component.saving()).toBe(false);
    expect(component.error()).toBe(
      'auth.management.catalogWorkspace.authoring.errors.conflict'
    );
  });

  it('keeps one save in flight and prevents duplicate submission', () => {
    const save = new Subject<AdminCatalogProductDetail>();
    const { component, catalog } = configure(null);
    catalog.createProduct.mockReturnValue(save);
    component.form.patchValue({
      slug: 'pending-draft',
      en: {
        name: 'English',
        shortDescription: 'English short',
        description: 'English description'
      },
      ar: {
        name: 'العربية',
        shortDescription: 'وصف مختصر',
        description: 'الوصف العربي'
      }
    });

    component.submit();
    component.submit();

    expect(component.saving()).toBe(true);
    expect(catalog.createProduct).toHaveBeenCalledTimes(1);
    save.next(PRODUCT);
    save.complete();
    expect(component.saving()).toBe(false);
  });
});
