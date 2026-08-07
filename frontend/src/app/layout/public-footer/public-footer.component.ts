import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { BgpBrandComponent } from '../../shared/ui/branding/bgp-brand.component';

@Component({
  selector: 'bgp-public-footer',
  standalone: true,
  imports: [RouterLink, TranslateModule, BgpBrandComponent],
  templateUrl: './public-footer.component.html',
  styleUrl: './public-footer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicFooterComponent {}
