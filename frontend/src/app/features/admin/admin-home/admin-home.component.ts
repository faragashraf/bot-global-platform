import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'bgp-admin-home',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './admin-home.component.html',
  styleUrl: './admin-home.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminHomeComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}
