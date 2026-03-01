import { Routes } from '@angular/router';
import { ScreenManagerComponent } from './components/screen-manager/screen-manager.component';
import { ViewerComponent } from './components/viewer/viewer.component';

export const routes: Routes = [
  { path: '', component: ScreenManagerComponent },
  { path: 'app/:id', component: ViewerComponent },
];
