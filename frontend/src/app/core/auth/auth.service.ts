import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import {
  ADMINISTRATOR_ROLE,
  AuthenticatedUser,
  LoginRequest
} from './auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly userState = signal<AuthenticatedUser | null>(null);
  private readonly initializedState = signal(false);
  private readonly busyState = signal(false);

  readonly user = this.userState.asReadonly();
  readonly initialized = this.initializedState.asReadonly();
  readonly busy = this.busyState.asReadonly();

  readonly isAuthenticated = computed(() => this.userState() !== null);
  readonly isAdministrator = computed(() =>
    this.userState()?.roles.includes(ADMINISTRATOR_ROLE) ?? false
  );

  async restoreSession(force = false): Promise<AuthenticatedUser | null> {
    if (this.initializedState() && !force) {
      return this.userState();
    }

    try {
      const user = await firstValueFrom(
        this.http.get<AuthenticatedUser>('/api/identity/me', {
          withCredentials: true
        })
      );

      this.userState.set(user);
      return user;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.userState.set(null);
        return null;
      }

      this.userState.set(null);
      throw error;
    } finally {
      this.initializedState.set(true);
    }
  }

  async login(request: LoginRequest): Promise<AuthenticatedUser> {
    this.busyState.set(true);

    try {
      await firstValueFrom(
        this.http.post<void>(
          '/api/identity/login',
          request,
          { withCredentials: true }
        )
      );

      const user = await this.restoreSession(true);

      if (!user) {
        throw new Error('Authenticated session was not established.');
      }

      return user;
    } finally {
      this.busyState.set(false);
    }
  }

  async logout(): Promise<void> {
    this.busyState.set(true);

    try {
      await firstValueFrom(
        this.http.post<void>(
          '/api/identity/logout',
          {},
          { withCredentials: true }
        )
      );
    } finally {
      this.userState.set(null);
      this.initializedState.set(true);
      this.busyState.set(false);
    }
  }
}
