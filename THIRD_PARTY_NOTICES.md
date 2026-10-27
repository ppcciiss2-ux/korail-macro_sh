# Third-party notices

MobileCompatibility.kt adapts the SDK 2.0.1 diagnostic provider from
[korail-mobile-api v3.0.0](https://github.com/yakisoba0728/korail-mobile-api/tree/b62e72b5b95ce8a98fbaa1cb0c65e1ba67ae0307),
`src/korail_mobile_api/_core/dynapath.py`.

Copyright (c) 2026 yakisoba0728. Licensed under the Apache License, Version 2.0.
The license is included in `licenses/Apache-2.0.txt`.
Modified for Kotlin: constant diagnostic token only; no synthetic device identity.

The upstream v4.0.0 removed login and reservation support. This app does not claim
compatibility based on v4.0.0. AppVersion 7.0.10 / Version 250722001 and the diagnostic
header are a compatibility candidate, not a guarantee of ongoing server acceptance.
