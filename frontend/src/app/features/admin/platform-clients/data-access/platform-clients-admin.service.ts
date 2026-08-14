import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CreatePlatformClientRequest,
  CreatedPlatformClient,
  PlatformClientListItem,
  RotatedPlatformClientCredential,
} from '../models/platform-client.models';

@Injectable({ providedIn: 'root' })
export class PlatformClientsAdminService {
  private readonly http = inject(HttpClient);
  private readonly resourceUrl = '/api/admin/platform-clients';

  list(): Observable<PlatformClientListItem[]> {
    return this.http.get<PlatformClientListItem[]>(this.resourceUrl);
  }

  create(request: CreatePlatformClientRequest): Observable<CreatedPlatformClient> {
    return this.http.post<CreatedPlatformClient>(this.resourceUrl, request);
  }

  rotateCredential(clientId: string): Observable<RotatedPlatformClientCredential> {
    return this.http.post<RotatedPlatformClientCredential>(`${this.resourceUrl}/${clientId}/credentials/rotate`, {});
  }

  revokeCredential(clientId: string, credentialId: string): Observable<void> {
    return this.http.post<void>(`${this.resourceUrl}/${clientId}/credentials/${credentialId}/revoke`, {});
  }
}
