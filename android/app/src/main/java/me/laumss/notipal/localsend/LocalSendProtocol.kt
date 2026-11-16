package me.laumss.notipal.localsend

import android.os.FileObserver
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.clipboard.ClipboardIo
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Http
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.ui_common.Dialogs
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.math.BigInteger
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

internal object LocalSendSecurity {
    private const val TAG = "LocalSendModule"

    data class StoredSecurityContext(
        val keyAlias: String,
        val privateKey: String,
        val publicKey: String,
        val certificate: String,
        val certificateHash: String,
    )

    val trustAllSsl: SSLContext by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        })
        SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }
    }
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    private const val SECURITY_CONTEXT_KEY = "ls_security_context"
    private const val KEY_ALIAS = "inkling_localsend_tls_pem_v1"
    private val KEYSTORE_PASSWORD = "inkling".toCharArray()

    @Volatile var serverTlsContext: SSLContext? = null
        private set
    @Volatile private var storedSecurityContext: StoredSecurityContext? = null

    @Synchronized
    fun ensureSecurityContext(): StoredSecurityContext {
        storedSecurityContext?.let { return it }

        val prefs = Prefs.of("localsend")
        val stored = prefs.getString(SECURITY_CONTEXT_KEY, null)
            ?.let { parseStoredSecurityContext(it) }
            ?.takeIf { it.keyAlias == KEY_ALIAS && it.privateKey.contains("BEGIN PRIVATE KEY") && it.certificate.contains("BEGIN CERTIFICATE") }

        val context = stored ?: loadOrGenerateSecurityContext()
        if (stored == null) {
            prefs.edit().putString(SECURITY_CONTEXT_KEY, securityContextToJson(context)).apply()
        }
        storedSecurityContext = context
        return context
    }

    fun initServerTlsContext() {
        if (serverTlsContext != null) return
        try {
            val securityContext = ensureSecurityContext()
            val privateKey = parsePrivateKeyPem(securityContext.privateKey)
            val certificate = parseCertificatePem(securityContext.certificate)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry(securityContext.keyAlias, privateKey, KEYSTORE_PASSWORD, arrayOf(certificate))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, KEYSTORE_PASSWORD)
            val keyManagers = kmf.keyManagers.map { km ->
                if (km is X509KeyManager) AliasKeyManager(km, securityContext.keyAlias) else km
            }.toTypedArray()
            serverTlsContext = SSLContext.getInstance("TLS").apply {
                init(keyManagers, null, SecureRandom())
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "[LS-DIAG] server TLS context initialized fingerprint=${securityContext.certificateHash}")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[LS-DIAG] server TLS init exception", e)
        }
    }

    fun serverProtocol(): String = if (serverTlsContext != null) "https" else "http"

    private fun parseStoredSecurityContext(raw: String): StoredSecurityContext? {
        return try {
            val o = JSONObject(raw)
            StoredSecurityContext(
                keyAlias = o.optString("keyAlias", KEY_ALIAS),
                privateKey = o.optString("privateKey", ""),
                publicKey = o.optString("publicKey", ""),
                certificate = o.optString("certificate", ""),
                certificateHash = o.optString("certificateHash", ""),
            ).takeIf { it.certificateHash.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "stored security context unreadable: ${e.message}")
            null
        }
    }

    private fun securityContextToJson(context: StoredSecurityContext): String =
        JSONObject().apply {
            put("keyAlias", context.keyAlias)
            put("privateKey", context.privateKey)
            put("publicKey", context.publicKey)
            put("certificate", context.certificate)
            put("certificateHash", context.certificateHash)
        }.toString()

    private fun loadOrGenerateSecurityContext(): StoredSecurityContext {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()
        val cert = generateSelfSignedCertificate(keyPair)

        return StoredSecurityContext(
            keyAlias = KEY_ALIAS,
            privateKey = pemBlock("PRIVATE KEY", keyPair.private.encoded),
            publicKey = pemBlock("PUBLIC KEY", cert.publicKey.encoded),
            certificate = pemBlock("CERTIFICATE", cert.encoded),
            certificateHash = LocalSendClient.sha256Hex(cert.encoded),
        )
    }

    private fun parsePrivateKeyPem(pem: String): PrivateKey {
        val der = parsePemBlock(pem)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun parseCertificatePem(pem: String): X509Certificate {
        val der = parsePemBlock(pem)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun parsePemBlock(pem: String): ByteArray {
        val content = pem.replace("\r\n", "\n")
            .split("\n")
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
        return android.util.Base64.decode(content, android.util.Base64.DEFAULT)
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 10L))
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val algorithm = derSequence(
            derOid("1.2.840.113549.1.1.11"),
            derNull(),
        )
        val name = derName("LocalSend User")
        val tbs = derSequence(
            derInteger(serial),
            algorithm,
            name,
            derSequence(derTime(notBefore), derTime(notAfter)),
            name,
            keyPair.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(tbs)
        }.sign()
        val certDer = derSequence(tbs, algorithm, derBitString(sig))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun pemBlock(type: String, der: ByteArray): String {
        val base64 = android.util.Base64.encodeToString(
            der,
            android.util.Base64.NO_WRAP,
        )
        val body = base64.chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
    }

    private fun derSequence(vararg parts: ByteArray): ByteArray =
        derTagged(0x30, parts.flatMap { it.asIterable() }.toByteArray())

    private fun derSet(vararg parts: ByteArray): ByteArray =
        derTagged(0x31, parts.flatMap { it.asIterable() }.toByteArray())

    private fun derTagged(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(value.size) + value

    private fun derLength(length: Int): ByteArray {
        if (length < 128) return byteArrayOf(length.toByte())
        val bytes = mutableListOf<Byte>()
        var n = length
        while (n > 0) {
            bytes.add(0, (n and 0xff).toByte())
            n = n ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun derInteger(value: BigInteger): ByteArray =
        derTagged(0x02, value.toByteArray())

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derOid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toLong() }
        val encoded = mutableListOf<Byte>()
        encoded.add((parts[0] * 40 + parts[1]).toByte())
        for (part in parts.drop(2)) {
            val stack = mutableListOf<Byte>()
            var n = part
            stack.add((n and 0x7f).toByte())
            n = n ushr 7
            while (n > 0) {
                stack.add(0, ((n and 0x7f) or 0x80).toByte())
                n = n ushr 7
            }
            encoded.addAll(stack)
        }
        return derTagged(0x06, encoded.toByteArray())
    }

    private fun derUtf8(value: String): ByteArray =
        derTagged(0x0c, value.toByteArray(Charsets.UTF_8))

    private fun derName(commonName: String): ByteArray =
        derSequence(
            derSet(
                derSequence(
                    derOid("2.5.4.3"),
                    derUtf8(commonName),
                ),
            ),
        )

    private fun derTime(date: Date): ByteArray {
        val text = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date)
        return derTagged(0x17, text.toByteArray(Charsets.US_ASCII))
    }

    private fun derBitString(value: ByteArray): ByteArray =
        derTagged(0x03, byteArrayOf(0x00) + value)

    private class AliasKeyManager(
        private val delegate: X509KeyManager,
        private val alias: String,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? =
            if (isRsaKeyType(keyType)) arrayOf(alias) else delegate.getClientAliases(keyType, issuers)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<java.security.Principal>?,
            socket: Socket?,
        ): String? =
            if (keyType?.any { isRsaKeyType(it) } == true) alias else delegate.chooseClientAlias(keyType, issuers, socket)

        override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String> =
            if (isRsaKeyType(keyType)) arrayOf(alias) else emptyArray()

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<java.security.Principal>?,
            socket: Socket?,
        ): String? =
            if (isRsaKeyType(keyType)) alias else delegate.chooseServerAlias(keyType, issuers, socket)

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            delegate.getCertificateChain(this.alias)

        override fun getPrivateKey(alias: String?): PrivateKey? =
            delegate.getPrivateKey(this.alias)

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<java.security.Principal>?,
            engine: SSLEngine?,
        ): String? =
            if (keyType?.any { isRsaKeyType(it) } == true) alias else null

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<java.security.Principal>?,
            engine: SSLEngine?,
        ): String? =
            if (isRsaKeyType(keyType)) alias else null

        private fun isRsaKeyType(keyType: String?): Boolean {
            val normalized = keyType?.uppercase(Locale.US) ?: return false
            return normalized == "RSA" || normalized.startsWith("RSA_")
        }
    }

    private class PrereadSocket(private val delegate: Socket, prefix: ByteArray) : Socket() {
        private val input: InputStream =
            SequenceInputStream(ByteArrayInputStream(prefix), delegate.getInputStream())

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = delegate.getOutputStream()
        override fun getInetAddress(): InetAddress? = delegate.inetAddress
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
        override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isBound(): Boolean = delegate.isBound
        override fun isClosed(): Boolean = delegate.isClosed
        override fun isInputShutdown(): Boolean = delegate.isInputShutdown
        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
        override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
        override fun getKeepAlive(): Boolean = delegate.keepAlive
        override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
        override fun getSoLinger(): Int = delegate.soLinger
        override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
        override fun getReuseAddress(): Boolean = delegate.reuseAddress
        override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
        override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
        override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
        override fun getSendBufferSize(): Int = delegate.sendBufferSize
        override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun close() = delegate.close()
        override fun toString(): String = "PrereadSocket($delegate)"
    }

    fun wrapServerTlsSocket(tls: SSLContext, socket: Socket, consumed: ByteArray): SSLSocket {
        val host = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""
        return tls.socketFactory.createSocket(
            PrereadSocket(socket, consumed), host, socket.port, true,
        ) as SSLSocket
    }
}

internal object LocalSendClient {
    private const val TAG = "LocalSendModule"
    const val PROTOCOL_VERSION = "2.0"
    const val DEFAULT_PORT = 53317
    const val API_BASE = "/api/localsend/v2"
    const val API_BASE_V1 = "/api/localsend/v1"

    private const val PEER_TTL_MS = 15_000L

    private const val PREPARE_UPLOAD_READ_TIMEOUT_MS = 75_000

    private const val DEFAULT_PEER_KEY = "localsend_default_peer"

    @Volatile var deviceAlias: String = "Supernote"
    @Volatile var deviceFingerprint: String = ""

    @Volatile var serverPort = DEFAULT_PORT

    val discoveredPeers: ConcurrentHashMap<String, DiscoveredPeer> = ConcurrentHashMap()

    fun peersSnapshot(): List<DiscoveredPeer> {
        val now = System.currentTimeMillis()
        discoveredPeers.entries.removeIf {
            now - it.value.lastSeen > PEER_TTL_MS ||
                LocalSendDiscovery.isLocalAddress(it.value.ip) ||
                it.value.fingerprint == deviceFingerprint
        }
        return discoveredPeers.values.toList()
    }

    fun defaultPeer(): DiscoveredPeer? {
        return try {
            val json = Prefs.of("localsend").getString(DEFAULT_PEER_KEY, null) ?: return null
            val o = JSONObject(json)
            val fp = o.optString("fingerprint", "")
            if (fp.isEmpty()) null else DiscoveredPeer(
                alias = o.optString("alias", "?"),
                ip = o.optString("ip", ""),
                port = o.optInt("port", DEFAULT_PORT),
                deviceType = o.optString("deviceType", "desktop"),
                fingerprint = fp,
                useTls = o.optBoolean("useTls", true),
            )
        } catch (e: Exception) {
            Log.w(TAG, "default peer unreadable: ${e.message}")
            null
        }
    }

    fun setDefaultPeer(peer: DiscoveredPeer?) {
        val prefs = Prefs.of("localsend")
        if (peer == null) {
            prefs.edit().remove(DEFAULT_PEER_KEY).apply()
            return
        }
        val o = JSONObject().apply {
            put("alias", peer.alias)
            put("ip", peer.ip)
            put("port", peer.port)
            put("deviceType", peer.deviceType)
            put("fingerprint", peer.fingerprint)
            put("useTls", peer.useTls)
        }
        prefs.edit().putString(DEFAULT_PEER_KEY, o.toString()).apply()
    }

    fun probeDefaultPeer() {
        val def = defaultPeer() ?: return
        if (def.ip.isEmpty()) return
        thread(isDaemon = true, name = "LocalSend-ProbeDefault") {
            PluginIdentity.claim()
            probeHost(def.ip, def.port)
        }
    }

    @Volatile private var reprobeRunning = false

    fun refreshPeers() {
        val known = discoveredPeers.values.map { it.ip to it.port }.distinct()
        discoveredPeers.clear()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "refreshPeers: cleared, reprobe ${known.size} known")
        probeDefaultPeer()
        reprobe(known)
        triggerScan()
    }

    fun reprobeKnownPeers() {
        reprobe(discoveredPeers.values.map { it.ip to it.port }.distinct())
    }

    private fun reprobe(known: List<Pair<String, Int>>) {
        if (reprobeRunning) return
        if (known.isEmpty()) return
        thread(isDaemon = true, name = "NativePanel-Reprobe") {
            PluginIdentity.claim()
            reprobeRunning = true
            try {
                for ((ip, port) in known) probeHost(ip, port)
            } finally {
                reprobeRunning = false
            }
        }
    }

    fun openConn(url: String, connectTimeout: Int = 10000, readTimeout: Int = 30000): HttpURLConnection {

        PluginIdentity.claim()
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = LocalSendSecurity.trustAllSsl.socketFactory
            conn.hostnameVerifier = LocalSendSecurity.hostnameVerifier
        }
        conn.connectTimeout = connectTimeout
        conn.readTimeout = readTimeout
        return conn
    }

    private class UploadBody(val length: Long, val open: () -> InputStream) {
        constructor(bytes: ByteArray) : this(bytes.size.toLong(), { ByteArrayInputStream(bytes) })
        constructor(file: File) : this(file.length(), { FileInputStream(file) })
    }

    fun httpPostJson(url: String, jsonBody: String, readTimeout: Int = 30000): String =
        post(url, "application/json; charset=utf-8", UploadBody(jsonBody.toByteArray(Charsets.UTF_8)), readTimeout)

    private fun httpPostBinary(url: String, data: UploadBody) {
        post(url, "application/octet-stream", data, 60000)
    }

    private fun post(url: String, contentType: String, data: UploadBody, readTimeout: Int): String {
        if (url.startsWith("http://")) {
            return data.open().use { input ->
                Http.request(
                    "POST", url, mapOf("Content-Type" to contentType),
                    connectTimeoutMs = 10000, readTimeoutMs = readTimeout,
                    bodyStream = input, bodyLength = data.length,
                ).use { resp ->
                    val text = resp.body.readBytes().toString(Charsets.UTF_8)
                    if (!resp.isSuccess) throw IOException("HTTP ${resp.status}: ${text.ifEmpty { "no body" }}")
                    text
                }
            }
        }
        val conn = openConn(url, readTimeout = readTimeout)
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(data.length)
            conn.doOutput = true
            conn.outputStream.use { out -> data.open().use { it.copyTo(out) } }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.readText()
                } catch (e: Exception) {
                    Log.d(TAG, "error body unreadable: ${e.message}")
                    null
                }
                throw IOException("HTTP $code: ${err ?: "no body"}")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildSingleFileJson(
        fileId: String, fileName: String, size: Long, fileType: String,
        sha: String, preview: String? = null,
    ): JSONObject = JSONObject().apply {
        put(fileId, JSONObject().apply {
            put("id", fileId); put("fileName", fileName)
            put("size", size); put("fileType", fileType)
            put("hash", sha)
            put("sha256", sha)
            if (preview != null) put("preview", preview)
        })
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
            "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    @Throws(Exception::class)
    fun sendTextDirect(ip: String, port: Int, text: String, useTls: Boolean = true): String {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val fileId = "text-${UUID.randomUUID().toString().substring(0, 8)}"
        val filesJson = buildSingleFileJson(
            fileId, "message.txt", textBytes.size.toLong(), "text/plain",
            sha256Hex(textBytes), preview = text,
        )
        return doUploadDirect(ip, port, filesJson, mapOf(fileId to UploadBody(textBytes)), useTls)
    }

    @Throws(Exception::class)
    fun sendFileDirect(ip: String, port: Int, filePath: String, useTls: Boolean = true): String {
        val file = File(filePath)
        if (!file.exists()) throw Exception("File not found: $filePath")
        val upload = UploadBody(file)
        val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
        val filesJson = buildSingleFileJson(
            fileId, file.name, upload.length, guessMimeType(file.name),
            sha256Hex(file),
        )
        return doUploadDirect(ip, port, filesJson, mapOf(fileId to upload), useTls)
    }

    @Throws(Exception::class)
    fun sendFilesDirect(ip: String, port: Int, filePaths: List<String>, useTls: Boolean = true): String {
        if (filePaths.size == 1) return sendFileDirect(ip, port, filePaths[0], useTls)
        val filesJson = JSONObject()
        val data = LinkedHashMap<String, UploadBody>()
        for (path in filePaths) {
            val file = File(path)
            if (!file.exists()) throw FileNotFoundException("File not found: $path")
            val upload = UploadBody(file)
            val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
            val one = buildSingleFileJson(
                fileId, file.name, upload.length, guessMimeType(file.name), sha256Hex(file),
            )
            filesJson.put(fileId, one.getJSONObject(fileId))
            data[fileId] = upload
        }
        return doUploadDirect(ip, port, filesJson, data, useTls)
    }

    fun localSendInfo(alias: String, fingerprint: String, port: Int): JSONObject = JSONObject().apply {
        put("alias", alias)
        put("version", PROTOCOL_VERSION)
        put("deviceModel", "Supernote")
        put("deviceType", "mobile")
        put("fingerprint", fingerprint)
        put("port", port)
        put("protocol", LocalSendSecurity.serverProtocol())
        put("download", false)
    }

    private fun doUploadDirect(
        ip: String, port: Int, filesJson: JSONObject, fileData: Map<String, UploadBody>,
        useTls: Boolean = true,
    ): String {
        val prepareBody = JSONObject().apply {
            put("info", localSendInfo(deviceAlias, deviceFingerprint, serverPort))
            put("files", filesJson)
        }
        try {

            val scheme = if (useTls) "https" else "http"
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND] doUploadDirect $scheme://$ip:$port")
            val prepareStart = System.currentTimeMillis()
            val prepareResp = httpPostJson("$scheme://$ip:$port$API_BASE/prepare-upload",
                prepareBody.toString(), readTimeout = PREPARE_UPLOAD_READ_TIMEOUT_MS)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "[SEND] prepare-upload in ${System.currentTimeMillis() - prepareStart}ms response: $prepareResp")
            }
            val prepareJson = JSONObject(prepareResp)
            val sessionId = prepareJson.optString("sessionId", "")
            val tokenMap = prepareJson.optJSONObject("files")
            if (sessionId.isEmpty()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND] auto-accepted")
                return "auto"
            }
            if (tokenMap == null || tokenMap.length() == 0) {

                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND] no tokens issued, consumed at prepare-upload; done")
                return sessionId
            }
            for ((fileId, data) in fileData) {
                val token = tokenMap.optString(fileId, "")
                if (token.isEmpty()) {
                    throw IOException("prepare-upload returned no token for $fileId: $prepareResp")
                }
                val path = "$API_BASE/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND] uploading $fileId size=${data.length}")
                val uploadStart = System.currentTimeMillis()
                httpPostBinary("$scheme://$ip:$port$path", data)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND] upload $fileId done in ${System.currentTimeMillis() - uploadStart}ms")
            }
            return sessionId
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[SEND] doUploadDirect FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    @Volatile private var scanRunning = false

    fun triggerScan() {
        if (scanRunning) return
        thread(isDaemon = true, name = "NativePanel-Scan") {
            PluginIdentity.claim()
            scanRunning = true
            try {
                val localIp = localIp()
                if (localIp == "0.0.0.0") return@thread
                val localIps = LocalSendDiscovery.localIps()
                val subnet = localIp.substringBeforeLast('.')
                val executor = Executors.newFixedThreadPool(50)
                for (i in 1..254) {
                    val ip = "$subnet.$i"
                    if (ip in localIps) continue
                    executor.submit { probeHost(ip, DEFAULT_PORT) }
                }
                executor.shutdown()
                executor.awaitTermination(6, TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "triggerScan error", e)
            } finally {
                scanRunning = false
            }
        }
    }

    fun localIp(): String = LocalSendDiscovery.localIps().firstOrNull() ?: "0.0.0.0"

    private fun probeHost(ip: String, port: Int) {
        if (LocalSendDiscovery.isLocalAddress(ip)) return
        for (useTls in listOf(true, false)) {
            try {
                val body = if (useTls) {
                    val conn = openConn("https://$ip:$port$API_BASE/info", 1500, 1500)
                    try {
                        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
                    } finally {
                        conn.disconnect()
                    }
                } else {
                    Http.request("GET", "http://$ip:$port$API_BASE/info", connectTimeoutMs = 1500, readTimeoutMs = 1500)
                        .use { if (it.status == 200) it.body.readBytes().toString(Charsets.UTF_8) else null }
                }
                if (body != null) {
                    registerPeerFromJson(body, ip, port, useTls)
                    return
                }
            } catch (e: IOException) {

                Log.d(TAG, "probe $ip:$port (tls=$useTls) unreachable: ${e.message}")
                continue
            } catch (e: Exception) {
                Log.d(TAG, "probe $ip:$port (tls=$useTls) failed: ${e.message}")
            }
        }
    }

    private fun registerPeerFromJson(body: String, ip: String, port: Int, useTls: Boolean = true) {
        if (LocalSendDiscovery.isLocalAddress(ip)) return
        val data = JSONObject(body)
        val fp = data.optString("fingerprint", "")
        if (fp.isNotEmpty() && fp != deviceFingerprint) {
            discoveredPeers[fp] = DiscoveredPeer(
                data.optString("alias", "Unknown"), ip,
                data.optInt("port", port), data.optString("deviceType", "desktop"), fp,
                useTls = useTls,
            )
        }
    }
}

internal object LocalSendServer {
    private const val TAG = "LocalSendModule"
    private const val DEFAULT_PORT = LocalSendClient.DEFAULT_PORT
    private const val API_BASE = LocalSendClient.API_BASE
    private const val API_BASE_V1 = LocalSendClient.API_BASE_V1

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var running = false
        private set
    private var serverPort = DEFAULT_PORT
    private var deviceAlias = "Supernote-${(1000..9999).random()}"
    private var deviceFingerprint = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var receiveDir = "/sdcard/LocalSend"
    private var pin = ""

    private val uploadSessions = ConcurrentHashMap<String, UploadSession>()
    @Volatile private var activeUploadSession: String? = null

    data class FileInfo(
        val id: String,
        val fileName: String,
        val size: Long,
        val fileType: String,
        val sha256: String,
    )

    data class UploadSession(
        val sessionId: String,
        val senderIp: String,
        var senderAlias: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val timeout: Long = 600_000L,
        val files: MutableMap<String, FileInfo> = mutableMapOf(),
        val tokens: MutableMap<String, String> = mutableMapOf(),
        val received: MutableMap<String, Boolean> = mutableMapOf(),
        var insertImagesImmediately: Boolean = false,
    ) {
        fun isValid(): Boolean = (System.currentTimeMillis() - createdAt) < timeout
    }

    fun forceCloseAll() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.d(TAG, "server socket close: ${e.message}")
        }
        serverSocket = null
        LocalSendDiscovery.stopLocalSendDiscovery()
    }

    @Synchronized
    fun start(alias: String, port: Int, dest: String, pin: String): Boolean {
        if (running) return false

        forceCloseAll()

        try {
            deviceAlias = alias
            serverPort = port
            receiveDir = dest
            this.pin = pin

            LocalSendClient.deviceAlias = deviceAlias
            val securityContext = LocalSendSecurity.ensureSecurityContext()
            deviceFingerprint = securityContext.certificateHash
            LocalSendClient.deviceFingerprint = deviceFingerprint

            File(receiveDir).mkdirs()

            LocalSendSecurity.initServerTlsContext()

            val sock = bindServerSocket()
            running = true
            thread(isDaemon = true, name = "LocalSend-Server") { acceptLoop(sock) }

            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "startServer: bound HTTP port=$serverPort, localIp=${LocalSendClient.localIp()}, receiveDir=$receiveDir")
            }

            LocalSendDiscovery.startLocalSendDiscovery(
                App.context,
                announcementProvider = { buildLocalSendAnnouncement() },
                onLocalSendAnnouncement = { senderIp, dto -> handleLocalSendAnnouncement(senderIp, dto) },
            )
            LocalSendDiscovery.requestLocalSendAnnounce("server start")

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "LocalSend server started on ${LocalSendClient.localIp()}:$serverPort")
            return true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Failed to start server", e)
            running = false
            throw e
        }
    }

    fun stop() {
        forceCloseAll()
        serverPort = DEFAULT_PORT
        LocalSendClient.serverPort = DEFAULT_PORT
        uploadSessions.clear()
        activeUploadSession = null
        LocalSendClient.discoveredPeers.clear()
    }

    private fun bindServerSocket(): ServerSocket {
        val sock = ServerSocket()
        sock.reuseAddress = true
        for (attempt in 0..9) {
            val tryPort = serverPort + attempt
            try {
                sock.bind(InetSocketAddress(tryPort))
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] bind OK on tcp/$tryPort")
                serverPort = tryPort
                LocalSendClient.serverPort = tryPort
                serverSocket = sock
                return sock
            } catch (e: BindException) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[LS-DIAG] bind failed on tcp/$tryPort (${e.message}), trying next")
            }
        }
        sock.close()
        throw BindException("No available port in range $serverPort..${serverPort + 9}")
    }

    private fun acceptLoop(sock: ServerSocket) {

        PluginIdentity.claim()
        while (running) {
            try {
                val client = sock.accept()
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] accepted tcp connection from ${client.remoteSocketAddress} on local=${client.localSocketAddress}")
                }
                thread(isDaemon = true) { handleClient(client) }
            } catch (e: IOException) {
                if (running && BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Socket accept error", e)
                if (sock.isClosed) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var effectiveSocket: Socket = socket
        try {
            socket.soTimeout = 30000
            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "0.0.0.0"

            val raw = socket.getInputStream()
            val firstByte = raw.read()
            if (firstByte < 0) return

            val input: BufferedInputStream
            val output: BufferedOutputStream
            if (firstByte == 0x16) {
                val tls = LocalSendSecurity.serverTlsContext
                if (tls == null) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "[RECV] TLS ClientHello from $remoteIp but no server TLS context; closing")
                    }
                    return
                }
                val sslSocket = try {
                    LocalSendSecurity.wrapServerTlsSocket(tls, socket, byteArrayOf(0x16)).apply {
                        useClientMode = false
                        startHandshake()
                    }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "[RECV] TLS handshake failed from $remoteIp: ${e.javaClass.simpleName}: ${e.message}" +
                            (e.cause?.let { " (cause: ${it.javaClass.simpleName}: ${it.message})" } ?: ""))
                    }
                    return
                }
                effectiveSocket = sslSocket
                input = BufferedInputStream(sslSocket.inputStream)
                output = BufferedOutputStream(sslSocket.outputStream)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV] TLS session established with $remoteIp")
            } else {
                input = BufferedInputStream(
                    SequenceInputStream(ByteArrayInputStream(byteArrayOf(firstByte.toByte())), raw),
                )
                output = BufferedOutputStream(socket.outputStream)
            }

            val requestLine = readLine(input) ?: return
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV] request: $requestLine from $remoteIp")
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            var line = readLine(input)
            while (!line.isNullOrEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase()] =
                        line.substring(colonIdx + 1).trim()
                }
                line = readLine(input)
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val queryString = if (qIdx >= 0) rawPath.substring(qIdx + 1) else ""
            val queryParams = parseQuery(queryString)

            val apiPath = when {
                path.startsWith("$API_BASE/") -> path.removePrefix(API_BASE)
                path.startsWith("$API_BASE_V1/") -> path.removePrefix(API_BASE_V1)
                else -> path
            }

            when {
                method == "GET" && apiPath == "/info" ->
                    handleInfo(output)

                method == "POST" && apiPath == "/register" -> {
                    val body = readBody(input, contentLength)
                    handleRegister(output, body, remoteIp)
                }

                method == "POST" && apiPath == "/prepare-upload" -> {
                    val body = readBody(input, contentLength)
                    handlePrepareUpload(output, body, remoteIp, queryParams)
                }

                method == "POST" && apiPath == "/upload" ->
                    handleUpload(output, input, remoteIp, queryParams, contentLength)

                method == "POST" && apiPath == "/cancel" ->
                    handleCancel(output, queryParams)

                else ->
                    sendHttpResponse(output, 404, """{"error":"Not found"}""")
            }
        } catch (e: SSLException) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV] TLS error from client, ignoring: ${e.message}")
        } catch (e: SocketTimeoutException) {

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV] idle connection from ${socket.inetAddress?.hostAddress} closed after read timeout")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Client handler error", e)
        } finally {
            closeQuietly(effectiveSocket)
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.d(TAG, "socket close: ${e.message}")
        }
    }

    private fun handleInfo(output: BufferedOutputStream) {
        val info = deviceInfoJson()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] /info response body=$info")
        sendHttpResponse(output, 200, info.toString())
    }

    private fun deviceInfoJson() = LocalSendClient.localSendInfo(deviceAlias, deviceFingerprint, serverPort)

    private fun handleRegister(output: BufferedOutputStream, body: String, remoteIp: String) {
        try {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] /register from $remoteIp body=${body.take(500)}")
            val data = JSONObject(body)
            val peerAlias = data.optString("alias", "Unknown")
            val peerPort = data.optInt("port", DEFAULT_PORT)
            val peerDeviceType = data.optString("deviceType", "desktop")
            val peerFingerprint = data.optString("fingerprint", remoteIp)
            val peerProtocol = data.optString("protocol", "https")

            LocalSendClient.discoveredPeers[peerFingerprint] = DiscoveredPeer(
                alias = peerAlias,
                ip = remoteIp,
                port = peerPort,
                deviceType = peerDeviceType,
                fingerprint = peerFingerprint,
                useTls = peerProtocol != "http",
            )

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Device registered: $peerAlias from $remoteIp")

            val response = deviceInfoJson()
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            Log.w(TAG, "register body invalid from $remoteIp: ${e.message}")
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handlePrepareUpload(
        output: BufferedOutputStream, body: String,
        remoteIp: String, params: Map<String, String>,
    ) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV] handlePrepareUpload from $remoteIp body=${body.take(200)}")

        if (pin.isNotEmpty()) {
            val pinParam = params["pin"] ?: ""
            if (pinParam != pin) {
                val msg = if (pinParam.isEmpty()) "PIN required" else "Invalid PIN"
                sendHttpResponse(output, 401, """{"error":"$msg"}""")
                return
            }
        }

        activeUploadSession?.let { sid ->
            uploadSessions[sid]?.let { sess ->
                if (sess.isValid()) {
                    sendHttpResponse(output, 409, """{"error":"Blocked by another session"}""")
                    return
                }
            }
            activeUploadSession = null
        }

        try {
            val data = JSONObject(body)
            val files = data.optJSONObject("files")
            val info = data.optJSONObject("info")
            if (files == null || files.length() == 0) {
                sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
                return
            }

            val senderAlias = info?.optString("alias", "Unknown") ?: "Unknown"

            val allPreviews = mutableListOf<String>()
            val keys0 = files.keys()
            while (keys0.hasNext()) {
                val fileId = keys0.next()
                val fileData = files.getJSONObject(fileId)
                val preview = fileData.optString("preview", "")
                val fileType = fileData.optString("fileType", "")
                if (preview.isNotEmpty() && fileType.startsWith("text/")) {
                    allPreviews.add(preview)
                }
            }

            if (allPreviews.isNotEmpty() && allPreviews.size == files.length()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Text message(s) from [$senderAlias] via preview field: ${allPreviews.size}")
                val combined = allPreviews.joinToString("\n")
                LocalSend.deliverText(combined, "message.txt")

                val textSessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                val response = JSONObject().apply {
                    put("sessionId", textSessionId)
                    put("files", JSONObject())
                }
                sendHttpResponse(output, 200, response.toString())
                return
            }

            val sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
            val session = UploadSession(sessionId, remoteIp, senderAlias = senderAlias)

            val tokens = JSONObject()
            val fileNames = mutableListOf<String>()

            val keys = files.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val fileData = files.getJSONObject(fileId)
                val fileName = fileData.optString("fileName", "unknown")
                val fileSize = fileData.optLong("size", 0)
                val fileType = fileData.optString("fileType", "application/octet-stream")
                val sha256 = fileData.optString("sha256", "")

                val token = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                session.files[fileId] = FileInfo(fileId, fileName, fileSize, fileType, sha256)
                session.tokens[fileId] = token
                session.received[fileId] = false
                tokens.put(fileId, token)
                fileNames.add(fileName)
            }

            val isClipboardSync = fileNames.any { ClipboardIo.isSyncPackage(it) }

            val isSingleImage = fileNames.size == 1 && isImageFile(fileNames.first())

            if (isClipboardSync && !LocalSend.confirmClipboardSync(senderAlias)) {
                sendHttpResponse(output, 403, """{"error":"Rejected by user"}""")
                return
            }

            if (isSingleImage) {
                val choice = LocalSend.askReceivedImage(senderAlias)
                if (choice == Dialogs.ImageReceiveChoice.REJECT) {
                    sendHttpResponse(output, 403, """{"error":"Image rejected by user"}""")
                    return
                }
                session.insertImagesImmediately = choice == Dialogs.ImageReceiveChoice.INSERT_NOW
            }

            uploadSessions[sessionId] = session
            activeUploadSession = sessionId

            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "Accepted transfer from [$senderAlias]: ${fileNames.size} files")
                fileNames.forEach { Log.i(TAG, "  - $it") }
            }

            val response = JSONObject().apply {
                put("sessionId", sessionId)
                put("files", tokens)
            }
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "prepare-upload error", e)
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handleUpload(
        output: BufferedOutputStream, input: BufferedInputStream,
        remoteIp: String, params: Map<String, String>, contentLength: Int,
    ) {
        val sessionId = params["sessionId"] ?: ""
        val fileId = params["fileId"] ?: ""
        val token = params["token"] ?: ""

        if (sessionId.isEmpty() || fileId.isEmpty() || token.isEmpty()) {
            sendHttpResponse(output, 400, """{"error":"Missing parameters"}""")
            return
        }

        val session = uploadSessions[sessionId]
        if (session == null || !session.isValid()) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.tokens[fileId] != token) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.senderIp != remoteIp) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }

        val fileInfo = session.files[fileId]
        if (fileInfo == null) {
            sendHttpResponse(output, 400, """{"error":"Invalid file"}""")
            return
        }

        val ext = fileInfo.fileName.substringAfterLast('.', "").lowercase()

        val saveDir = when {
            ClipboardIo.isSyncPackage(fileInfo.fileName) -> ClipboardIo.syncInbox
            ext == "snplg" -> File(LocalSend.PACKAGE_DIR)
            else -> File(receiveDir)
        }
        saveDir.mkdirs()
        val destFile = safeFileName(fileInfo.fileName, saveDir)

        if (destFile.canonicalFile.parentFile != saveDir.canonicalFile) {
            Log.w(TAG, "[RECV] refused file name outside ${saveDir.absolutePath}: ${fileInfo.fileName}")
            sendHttpResponse(output, 400, """{"error":"Invalid file name"}""")
            return
        }

        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "[LS-DIAG] receive target dir=${saveDir.absolutePath} exists=${saveDir.exists()} canWrite=${saveDir.canWrite()} dest=$destFile")
            Log.i(TAG, "Receiving file: ${fileInfo.fileName} -> $destFile")
        }

        try {
            var received = 0L
            val sha = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            val total = if (contentLength > 0) contentLength.toLong() else fileInfo.size

            FileOutputStream(destFile).use { fos ->
                while (received < total) {
                    val toRead = minOf(buf.size.toLong(), total - received).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    received += n
                }
            }

            if (received < total) throw IOException("short read: $received of $total bytes")

            if (fileInfo.sha256.isNotEmpty()) {
                val computed = sha.digest().joinToString("") { "%02x".format(it) }
                if (!computed.equals(fileInfo.sha256, ignoreCase = true)) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "SHA256 mismatch for ${fileInfo.fileName}")
                }
            }

            session.received[fileId] = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "File received: ${fileInfo.fileName} -> $destFile ($received bytes)")

            if (ClipboardIo.isSyncPackage(fileInfo.fileName)) {

                ClipboardIo.receiveSync(destFile, session.senderAlias)
            } else if (isTextFile(fileInfo.fileName) || fileInfo.fileType.startsWith("text/")) {

                val textContent = destFile.readText(Charsets.UTF_8)
                LocalSend.deliverText(textContent, fileInfo.fileName)
            } else if (isImageFile(fileInfo.fileName) && session.insertImagesImmediately) {

                LocalSend.insertReceivedImage(destFile.absolutePath)
            }

            if (session.received.values.all { it }) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Session $sessionId complete!")
                activeUploadSession = null
            }

            sendHttpResponse(output, 200, "")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "File receive error", e)
            if (destFile.exists()) destFile.delete()

            uploadSessions.remove(sessionId)
            if (activeUploadSession == sessionId) activeUploadSession = null
            sendHttpResponse(output, 500, """{"error":"Unknown error by receiver"}""")
        }
    }

    private fun handleCancel(output: BufferedOutputStream, params: Map<String, String>) {
        val sessionId = params["sessionId"] ?: ""
        if (sessionId.isNotEmpty()) {
            uploadSessions.remove(sessionId)
            if (activeUploadSession == sessionId) {
                activeUploadSession = null
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Session cancelled: $sessionId")
        }
        sendHttpResponse(output, 200, "")
    }

    private fun buildLocalSendAnnouncement(): JSONObject = deviceInfoJson().put("announce", true)

    private fun handleLocalSendAnnouncement(senderIp: String, dto: JSONObject) {
        try {
            val fp = dto.optString("fingerprint", "")
            if (fp.isEmpty() || fp == deviceFingerprint) return
            val peerAlias = dto.optString("alias", "Unknown")
            val peerPort = dto.optInt("port", DEFAULT_PORT)
            val peerDeviceType = dto.optString("deviceType", "desktop")
            val peerProtocol = dto.optString("protocol", "https")
            val isAnnounce = dto.optBoolean("announce", dto.optBoolean("announcement", false))

            if (BuildConfig.ENABLE_DEBUG && LocalSendClient.discoveredPeers[fp] == null) {
                Log.i(TAG, "[LS-DIAG] multicast discovered peer $peerAlias @ $senderIp:$peerPort protocol=$peerProtocol announce=$isAnnounce")
            }

            LocalSendClient.discoveredPeers[fp] = DiscoveredPeer(
                alias = peerAlias, ip = senderIp, port = peerPort,
                deviceType = peerDeviceType, fingerprint = fp,
                useTls = peerProtocol != "http",
            )
            if (isAnnounce) {
                thread(isDaemon = true, name = "LocalSend-AnnounceReply") {
                    respondToAnnouncement(senderIp, peerPort, peerProtocol)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "announcement from $senderIp ignored: ${e.message}")
        }
    }

    private fun respondToAnnouncement(ip: String, port: Int, peerProtocol: String) {
        val body = deviceInfoJson()
        try {
            val scheme = if (peerProtocol == "http") "http" else "https"
            LocalSendClient.httpPostJson("$scheme://$ip:$port$API_BASE/register", body.toString())
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] register response sent to $peerProtocol://$ip:$port")
        } catch (e: Exception) {
            try {
                body.put("announce", false)
                LocalSendDiscovery.sendUnicast(ip, body)
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] register HTTP to $ip failed (${e.message}); UDP response sent instead")
                }
            } catch (e2: Exception) {
                Log.d(TAG, "UDP response to $ip failed: ${e2.message}")
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) {
                try {
                    URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8")
                } catch (e: Exception) {
                    Log.d(TAG, "query part ignored: ${e.message}")
                    null
                }
            } else {
                null
            }
        }.toMap()
    }

    private fun sendHttpResponse(output: BufferedOutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }

        val bodyBytes = body.toByteArray()
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        if (bodyBytes.isNotEmpty()) {
            output.write(bodyBytes)
        }
        output.flush()
    }

    private fun safeFileName(name: String, dir: File): File {
        val base = File(name).name
        var target = File(dir, base)
        if (!target.exists()) return target
        val dotIdx = base.lastIndexOf('.')
        val stem = if (dotIdx > 0) base.substring(0, dotIdx) else base
        val ext = if (dotIdx > 0) base.substring(dotIdx) else ""
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${stem}(${counter})${ext}")
            counter++
        }
        return target
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
    }

    private fun isTextFile(name: String): Boolean {
        return name == "message.txt"
    }
}
