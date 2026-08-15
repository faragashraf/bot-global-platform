import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CreatePlatformClientRequest,
  CreatedPlatformClient,
  PlatformCapabilityDescriptor,
  PlatformClientCapabilityState,
  PlatformClientListItem,
  RotatedPlatformClientCredential,
  SetPlatformClientCapabilitiesRequest,
} from '../models/platform-client.models';

@Injectable({ providedIn: 'root' })
export class PlatformClientsAdminService {
  private readonly http = inject(HttpClient);
  private readonly resourceUrl = '/api/admin/platform-clients';

  list(): Observable<PlatformClientListItem[]> {
    return this.http.get<PlatformClientListItem[]>(this.resourceUrl);
  }


  getCapabilityCatalog():
    Observable<PlatformCapabilityDescriptor[]> {
    return this.http.get<PlatformCapabilityDescriptor[]>(
      `${this.resourceUrl}/capabilities`,
    );
  }

  getClientCapabilities(
    clientId: string,
  ): Observable<PlatformClientCapabilityState> {
    return this.http.get<PlatformClientCapabilityState>(
      `${this.resourceUrl}/${clientId}/capabilities`,
    );
  }

  setClientCapabilities(
    clientId: string,
    capabilities: string[],
  ): Observable<PlatformClientCapabilityState> {
    const request: SetPlatformClientCapabilitiesRequest = {
      capabilities,
    };

    return this.http.put<PlatformClientCapabilityState>(
      `${this.resourceUrl}/${clientId}/capabilities`,
      request,
    );
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
