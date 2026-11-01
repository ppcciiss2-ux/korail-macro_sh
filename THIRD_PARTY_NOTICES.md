# Third-party notices

The earlier SDK 2.0.1 diagnostic provider was adapted from
[korail-mobile-api v3.0.0](https://github.com/yakisoba0728/korail-mobile-api/tree/b62e72b5b95ce8a98fbaa1cb0c65e1ba67ae0307),
`src/korail_mobile_api/_core/dynapath.py`.

Copyright (c) 2026 yakisoba0728. Licensed under the Apache License, Version 2.0.
The license is included in `licenses/Apache-2.0.txt`.
That fixed diagnostic token was removed in 2.0.1.

The upstream v4.0.0 removed login and reservation support. This app does not claim
compatibility based on v4.0.0.

DeclarationToken.kt and declaration-native-vectors.json adapt the codec and recorded
synthetic native vectors from [pykorail commit a9ceed7](https://github.com/devgyurak/pykorail/tree/a9ceed7ceca753e73182f26f2949d31db13fb16e).
Copyright (c) 2026 devgyurak and pykorail contributors. Full license:
`licenses/pykorail.txt`, also included in APK assets. This license includes a
**Commercial Gain Restriction**, which applies to derivative works. Personal travel
and noncommercial personal projects are permitted; commercial booking services,
ticket resale for profit, and commercial distribution of derived products are not.

Modified for Kotlin: SHA-256 stream and custom base62 port; Android callers supply
actual Build values and an app-scoped Android ID (persistent installation fallback
if unavailable). No native library is bundled; hardware attestation is not implemented.
AppVersion 7.1.0 / API 261028001 follow upstream PR #158. Ongoing server acceptance
and actual login/booking must be verified separately on the target device.
