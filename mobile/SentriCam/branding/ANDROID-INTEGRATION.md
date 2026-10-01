# Android integration

Recommended working branch:

`chore/sentricam-brand-assets`

Copy the contents of `03-android/res/` into `app/src/main/res/`, then verify:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round" />
```

For Android 12+ system splash, use:

- `@color/splash_background`
- `@drawable/splash_logo`

The supplied `sentricam-splash-1080x1920.png` is a branded visual source.
Do not force it as a fixed full-screen image across every aspect ratio; use the
Android SplashScreen API and optionally show a short in-app branding screen.

Notification icon:

`@drawable/ic_notification`

The notification icon is monochrome by design. Android applies the notification
accent color at runtime.
