package com.fourgeailabs.bpwatch.mobile.adb

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt

/**
 * TLS pieces for modern wireless debugging (Galaxy Watch Ultra and friends).
 *
 * Two distinct TLS uses:
 * - Pairing port: TLS 1.3 immediately, via Conscrypt (we need its public
 *   exportKeyingMaterial API — Android's platform TLS doesn't expose the
 *   TLS exporter used to derive the SPAKE2 password).
 * - Connect/ADB port: after the cleartext CNXN → STLS upgrade, via the
 *   platform TLS stack with our self-signed client certificate.
 */
internal object AdbTls {

    /** Self-signed X.509 for our ADB RSA keypair (adbd never verifies the chain). */
    fun selfSignedCert(pair: KeyPair): X509Certificate {
        val now = Calendar.getInstance()
        val notBefore = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        val notAfter = (now.clone() as Calendar).apply { add(Calendar.YEAR, 30) }.time
        val name = X500Name("CN=BPWatch ADB")
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val builder = X509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            notBefore,
            notAfter,
            name,
            SubjectPublicKeyInfo.getInstance(pair.public.encoded),
        )
        // Mark as valid for TLS client authentication (adbd requests a client cert).
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth),
        )
        val holder = builder.build(signer)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
    }

    /** TLS context for the pairing port (Conscrypt provider). */
    fun pairingContext(pair: KeyPair, cert: X509Certificate): SSLContext {
        val ctx = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
        ctx.init(keyManagers(pair, cert), arrayOf(trustAll()), SecureRandom())
        return ctx
    }

    /** TLS context for the ADB port after the STLS upgrade (platform stack). */
    fun connectContext(pair: KeyPair, cert: X509Certificate): SSLContext {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(keyManagers(pair, cert), arrayOf(trustAll()), SecureRandom())
        return ctx
    }

    /** RFC 5705 exporter — the SPAKE2 password's second half. */
    fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray =
        Conscrypt.exportKeyingMaterial(socket, label, null, length)

    private fun keyManagers(pair: KeyPair, cert: X509Certificate) =
        arrayOf(object : X509ExtendedKeyManager() {
            // Always present our ADB client cert — adbd's CertificateRequest
            // CA list won't include our self-signed issuer, so the default
            // KeyManagerFactory would (and did) send nothing, triggering
            // TLSV1_ALERT_CERTIFICATE_REQUIRED from the watch.
            override fun getClientAliases(
                keyType: String?, issuers: Array<out Principal>?,
            ): Array<String> = arrayOf("adb")

            override fun chooseClientAlias(
                keyType: Array<out String>?, issuers: Array<out Principal>?,
                socket: Socket?,
            ): String = "adb"

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?, issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ): String = "adb"

            override fun getServerAliases(
                keyType: String?, issuers: Array<out Principal>?,
            ): Array<String> = emptyArray()

            override fun chooseServerAlias(
                keyType: String?, issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                arrayOf(cert)

            override fun getPrivateKey(alias: String?): PrivateKey =
                pair.private as PrivateKey
        })

    /** adbd presents a self-signed cert too — nothing to verify against. */
    private fun trustAll() = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    }
}
