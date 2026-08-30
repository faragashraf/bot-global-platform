import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AdminRevokeDeviceResult,
  DevicePairingDetail,
  DevicePairingListItem,
} from '../models/device-pairing.models';

@Injectable({ providedIn: 'root' })
export class DevicePairingAdminService {
  private readonly http = inject(HttpClient);

  private readonly resourceUrl =
    '/api/admin/device-pairings';

  list(): Observable<DevicePairingListItem[]> {
    return this.http.get<DevicePairingListItem[]>(
      this.resourceUrl,
    );
  }

  find(
    deviceId: string,
  ): Observable<DevicePairingDetail> {
    return this.http.get<DevicePairingDetail>(
      `${this.resourceUrl}/${deviceId}`,
    );
  }

  revoke(
    deviceId: string,
    purgeHistory: boolean,
  ): Observable<AdminRevokeDeviceResult> {
    return this.http.post<AdminRevokeDeviceResult>(
      `${this.resourceUrl}/${deviceId}/revoke`,
      { purgeHistory },
    );
  }
}
