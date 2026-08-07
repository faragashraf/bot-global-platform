import { definePreset } from '@primeng/themes';
import Aura from '@primeng/themes/aura';

export const BotGlobalPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#eef8ff',
      100: '#d9efff',
      200: '#bce3ff',
      300: '#8ed2ff',
      400: '#59b7ff',
      500: '#2f95ff',
      600: '#1777f2',
      700: '#125fdc',
      800: '#174daf',
      900: '#194487',
      950: '#132a52'
    },
    colorScheme: {
      light: {
        surface: {
          0: '#ffffff',
          50: '#f7f9fc',
          100: '#eef2f7',
          200: '#dfe6ef',
          300: '#cbd5e1',
          400: '#94a3b8',
          500: '#64748b',
          600: '#475569',
          700: '#334155',
          800: '#1e293b',
          900: '#0f172a',
          950: '#07111f'
        }
      },
      dark: {
        surface: {
          0: '#ffffff',
          50: '#f5f8fc',
          100: '#e6edf7',
          200: '#cbd7e7',
          300: '#a8b8ce',
          400: '#7e93ae',
          500: '#5f7592',
          600: '#435975',
          700: '#2d4059',
          800: '#1b2b40',
          900: '#101c2e',
          950: '#07111f'
        }
      }
    }
  }
});
