package com.point.data

import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * TLS pinning for the relay (#161 v2). The relay serves a **self-signed** cert (IP SAN
 * `35.185.31.106`), so the phone trusts exactly that one certificate — nothing else. Pinning IS the
 * security here; the cert has the server IP as its SAN, so the platform's hostname check passes on
 * its own. The certificate is public (not a secret), so it is embedded rather than shipped as a file.
 */
object RelayTls {

    // relay/cert.pem — self-signed, CN/SAN = 35.185.31.106, valid to 2036.
    private val CERT_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIDIjCCAgqgAwIBAgIUSFm99q9SioT9Tv7afRowGMzzM2UwDQYJKoZIhvcNAQEL
        BQAwGDEWMBQGA1UEAwwNMzUuMTg1LjMxLjEwNjAeFw0yNjA3MjgwNTQ0NTdaFw0z
        NjA3MjUwNTQ0NTdaMBgxFjAUBgNVBAMMDTM1LjE4NS4zMS4xMDYwggEiMA0GCSqG
        SIb3DQEBAQUAA4IBDwAwggEKAoIBAQCdUsFi+E7uv5kBDJGIYOXOkp3T0E390G5f
        1iFt5+t99nk0+A9pt4exn8F8w/eo29vyEClEPKyqVUieqGoSgobDFkfgSSay9hFX
        PiFMcnADpuNfnchx495N6Lwhcrzf5Y4i+8jk+tHVMsmWarIx7Zb3bL4QpnxgEDgn
        WEVuv0pGzX4RAlZETVwRs90qCmYOM7ZU/K4n0R6XnHIJvSRoFE7mu+Et3mCVsgzV
        WWZrKTwIrRpq334em5oHM0uBQLh1vu8XP+k5xc9oh5ywZAqgtT2YdWEvPJ4cV396
        vvOoz1jv6c7YK7S2YYf8gs9AUu8WlwjBuckZfwB/GBw51A17s6/tAgMBAAGjZDBi
        MB0GA1UdDgQWBBRia2q6bTY7hrbObKPIPeQ72xaRSTAfBgNVHSMEGDAWgBRia2q6
        bTY7hrbObKPIPeQ72xaRSTAPBgNVHRMBAf8EBTADAQH/MA8GA1UdEQQIMAaHBCO5
        H2owDQYJKoZIhvcNAQELBQADggEBAFoJaUJVTujSjfLzWxi5snymQQD+XMxuq/DJ
        72VDvRqGwoUEyquR7qcEXTY9BaldXo/sBHsFgazFHeOzs0hCyQJdM3VQ1Zh5d8b0
        pXu5+WVO/QM483nv5IbuX/ekIivyc+ofCwVmXt5vK1il2n33xuUXXUtLwYOwdGRc
        Caoyl40xmbXV1qtN494rJMuVA6okeRYmoGvLu4Bd+eNmYjJ6a/NZqjpvIBPcJZ2v
        Q+wiJR/n8t7s3matV4O0hVA86bCh/P3wkApvbKJiwTuRpd9OQhqMeZO5k+Cs3TR/
        AgbyDQoZMaspymGPykvDeHdNoEq/BsyCBjPB4OLwcMECZlAiR7g=
        -----END CERTIFICATE-----
    """.trimIndent()

    /** A socket factory that trusts ONLY the pinned relay certificate. */
    val socketFactory: SSLSocketFactory by lazy {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(CERT_PEM.byteInputStream()) as X509Certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("point-relay", cert)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}
