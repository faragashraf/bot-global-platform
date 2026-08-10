import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'bgp-login-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    TranslateModule
  ],
  templateUrl: './login-page.component.html',
  styleUrl: './login-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LoginPageComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly submitting = signal(false);
  readonly errorKey = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    userNameOrEmail: ['', [Validators.required, Validators.maxLength(256)]],
    password: ['', [Validators.required, Validators.maxLength(256)]],
    rememberMe: [false]
  });

  async submit(): Promise<void> {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }

    this.errorKey.set(null);
    this.submitting.set(true);

    try {
      const user = await this.auth.login(this.form.getRawValue());

      if (!user.roles.includes('Administrator')) {
        await this.auth.logout();
        this.errorKey.set('auth.login.adminRequired');
        return;
      }

      const requested =
        this.route.snapshot.queryParamMap.get('returnUrl');

      const returnUrl =
        requested?.startsWith('/admin')
          ? requested
          : '/admin';

      await this.router.navigateByUrl(returnUrl);
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) {
          this.errorKey.set('auth.login.invalidCredentials');
        } else if (error.status === 429) {
          this.errorKey.set('auth.login.tooManyAttempts');
        } else {
          this.errorKey.set('auth.login.unavailable');
        }
      } else {
        this.errorKey.set('auth.login.unavailable');
      }
    } finally {
      this.submitting.set(false);
    }
  }
}
