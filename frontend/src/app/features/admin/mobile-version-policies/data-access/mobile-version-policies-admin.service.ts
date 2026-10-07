import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  MobileVersionPolicyAdminItem,
  UpdateMobileVersionPolicyRequest,
} from '../models/mobile-version-policy.models';

@Injectable({ providedIn: 'root' })
export class MobileVersionPoliciesAdminService {
  private readonly http = inject(HttpClient);

  private readonly resourceUrl =
    '/api/admin/mobile-version-policies';

  list(): Observable<MobileVersionPolicyAdminItem[]> {
    return this.http.get<MobileVersionPolicyAdminItem[]>(
      this.resourceUrl,
    );
  }

  update(
    applicationKey: string,
    platform: string,
    request: UpdateMobileVersionPolicyRequest,
  ): Observable<MobileVersionPolicyAdminItem> {
    return this.http.put<MobileVersionPolicyAdminItem>(
      `${this.resourceUrl}/${encodeURIComponent(applicationKey)}/${encodeURIComponent(platform)}`,
      request,
    );
  }
}
