import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { BgpBrandComponent } from '../../shared/ui/branding/bgp-brand.component';

@Component({
  selector: 'bgp-public-footer',
  standalone: true,
  imports: [TranslateModule, BgpBrandComponent],
  templateUrl: './public-footer.component.html',
  styleUrl: './public-footer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicFooterComponent {}
