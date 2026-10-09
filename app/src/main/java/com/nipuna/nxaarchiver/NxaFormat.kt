package com.nipuna.nxaarchiver

import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * NXA — Nipuna eXtended Archive, Android/Kotlin implementation.
 *
 * On-disk layout:
 *   4 bytes   magic "NXA1"
 *   1 byte    flags  bit0=encrypted bit1=compressed bit2=has_manifest
 *   [if encrypted] 16 bytes salt, 12 bytes GCM nonce  (tag is appended
 *   by the JVM's GCM cipher at the end of the ciphertext automatically)
 *   N bytes   payload = container bytes, optionally XZ-compressed,
 *             optionally AES-256-GCM encrypted
 *
 * Container (pre-compression) is a simple length-prefixed multi-file
 * blob of our own design (not POSIX tar) — kept dependency-free:
 *   for each entry: [nameLen:Int][name utf8][dataLen:Long][data]
 *   entry count is prefixed as Int at the very start of the container
 */
object NxaFormat {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    const val FLAG_ENCRYPTED = 0b001
    const val FLAG_COMPRESSED = 0b010
    const val FLAG_MANIFEST = 0b100

    data class Entry(val name: String, val bytes: ByteArray)
    data class ArchiveInfo(val encrypted: Boolean, val compressed: Boolean, val hasManifest: Boolean, val size: Long)

    // ---------- public API ----------

    fun create(
        entries: List<Entry>,
        output: OutputStream,
        password: String?,
        bundle: Boolean,
        compress: Boolean = true
    ) {
        var container = packContainer(if (bundle) entries + buildManifestEntry(entries) else entries)
        var flags = 0

        if (compress) {
            container = xzCompress(container)
            flags = flags or FLAG_COMPRESSED
        }
        if (bundle) flags = flags or FLAG_MANIFEST

        val dos = DataOutputStream(output)
        dos.write(MAGIC)

        if (password != null) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val ciphertext = cipher.doFinal(container) // tag appended by JVM

            flags = flags or FLAG_ENCRYPTED
            dos.writeByte(flags)
            dos.write(salt)
            dos.write(nonce)
            dos.write(ciphertext)
        } else {
            dos.writeByte(flags)
            dos.write(container)
        }
        dos.flush()
    }

    fun readInfo(input: InputStream): ArchiveInfo {
        val bytes = input.readBytes()
        checkMagic(bytes)
        val flags = bytes[4].toInt()
        return ArchiveInfo(
            encrypted = flags and FLAG_ENCRYPTED != 0,
            compressed = flags and FLAG_COMPRESSED != 0,
            hasManifest = flags and FLAG_MANIFEST != 0,
            size = bytes.size.toLong()
        )
    }

    /** Returns the list of extracted entries (name -> bytes). Throws on bad password/corruption. */
    fun extract(input: InputStream, password: String?): List<Entry> {
        val bytes = input.readBytes()
        checkMagic(bytes)
        val flags = bytes[4].toInt()
        var offset = 5

        val container: ByteArray
        if (flags and FLAG_ENCRYPTED != 0) {
            requireNotNull(password) { "archive is encrypted, password required" }
            val salt = bytes.copyOfRange(offset, offset + 16); offset += 16
            val nonce = bytes.copyOfRange(offset, offset + 12); offset += 12
            val ciphertext = bytes.copyOfRange(offset, bytes.size)
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            container = try {
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw IllegalStateException("wrong password or corrupted archive", e)
            }
        } else {
            container = bytes.copyOfRange(offset, bytes.size)
        }

        val unpacked = if (flags and FLAG_COMPRESSED != 0) xzDecompress(container) else container
        return unpackContainer(unpacked)
    }

    // ---------- crypto / compression helpers ----------

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 200_000, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun xzCompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val opts = LZMA2Options(6)
        XZOutputStream(out, opts).use { it.write(data) }
        return out.toByteArray()
    }

    private fun xzDecompress(data: ByteArray): ByteArray {
        XZInputStream(ByteArrayInputStream(data)).use { return it.readBytes() }
    }

    private fun checkMagic(bytes: ByteArray) {
        require(bytes.size >= 5 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            "not a valid .nxa file"
        }
    }

    // ---------- container pack/unpack ----------

    private fun packContainer(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(entries.size)
        for (e in entries) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            dos.writeInt(nameBytes.size)
            dos.write(nameBytes)
            dos.writeLong(e.bytes.size.toLong())
            dos.write(e.bytes)
        }
        return out.toByteArray()
    }

    private fun unpackContainer(data: ByteArray): List<Entry> {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val count = dis.readInt()
        val result = ArrayList<Entry>(count)
        repeat(count) {
            val nameLen = dis.readInt()
            val nameBytes = ByteArray(nameLen).also { dis.readFully(it) }
            val dataLen = dis.readLong()
            val fileBytes = ByteArray(dataLen.toInt()).also { dis.readFully(it) }
            result.add(Entry(String(nameBytes, Charsets.UTF_8), fileBytes))
        }
        return result
    }

    private fun buildManifestEntry(entries: List<Entry>): Entry {
        val tools = JSONArray()
        for (e in entries) {
            val isScript = e.name.endsWith(".py") || e.name.endsWith(".sh")
            tools.put(JSONObject().apply {
                put("name", e.name)
                put("entrypoint", if (isScript) e.name else JSONObject.NULL)
                put("description", "(edit me)")
            })
        }
        val manifest = JSONObject().apply {
            put("format", "nxa-bundle")
            put("tools", tools)
        }
        return Entry("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
    }
}
