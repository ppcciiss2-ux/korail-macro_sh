package com.korailmacro.app.korail

import okhttp3.CertificatePinner

/** Additional pin check after normal Android TLS trust and hostname validation. */
internal object KorailTlsPolicy {
    val pinner: CertificatePinner = CertificatePinner.Builder()
        .add(
            "smart.letskorail.com",
            // Legacy chain, retained while server nodes may still use it.
            "sha256/+aKPhqKL0hOK1/1r5KYM4uKXDQ5kSOf5/2iSbcLtNms=",
            "sha256/hETpgVvaLC0bvcGG3t0cuqiHvr4XyP2MTwCiqhgRWwU=",
            "sha256/cGuxAXyFXFkWm61cF4HPWX8S0srS9j0aSqN0k4AP+4A=",
            // GlobalSign GCC R46 OV TLS CA 2025: verified via trusted TLS 2026-11-14 KST.
            // Intermediate pin survives leaf renewal; no trust-all or hostname bypass.
            "sha256/nZ4QsWxivBcuuFkI8dXgfa0Pb2o1sjZ8hKx6h+729xw="
        ).build()
}
