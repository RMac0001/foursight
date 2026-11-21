package io.github.rmac0001.foursight.core.secrets

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APITypeMapper

/**
 * [SecretStore] on Windows Credential Manager (generic credentials, protected by DPAPI for the
 * signed-in Windows user). Values are stored as UTF-16LE, which is what the Credential Manager
 * control panel writes when Roger enters a password there.
 */
class WindowsCredentialStore : SecretStore {
    init {
        check(System.getProperty("os.name").startsWith("Windows")) {
            "Windows Credential Manager is only available on Windows."
        }
    }

    override fun read(name: SecretName): String? = readTarget(name.target)

    override fun write(
        name: SecretName,
        value: String,
    ) = writeTarget(name.target, value)

    override fun delete(name: SecretName) = deleteTarget(name.target)

    // Raw targets are internal so tests can use a throwaway name and never touch Roger's entries.

    internal fun readTarget(target: String): String? {
        val out = PointerByReference()
        try {
            CredApi.INSTANCE.CredReadW(target, CRED_TYPE_GENERIC, 0, out)
        } catch (e: LastErrorException) {
            if (e.errorCode == ERROR_NOT_FOUND) return null
            throw e
        }
        val pointer = out.value
        try {
            val credential = Credential(pointer).apply { read() }
            val size = credential.CredentialBlobSize
            val blob = credential.CredentialBlob
            if (size == 0 || blob == null) return ""
            return String(blob.getByteArray(0, size), Charsets.UTF_16LE)
        } finally {
            CredApi.INSTANCE.CredFree(pointer)
        }
    }

    internal fun writeTarget(
        target: String,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_16LE)
        val blob = Memory(bytes.size.coerceAtLeast(1).toLong()).apply { write(0, bytes, 0, bytes.size) }
        val credential =
            Credential().apply {
                Type = CRED_TYPE_GENERIC
                TargetName = target
                UserName = "FourSight"
                CredentialBlobSize = bytes.size
                CredentialBlob = blob
                Persist = CRED_PERSIST_LOCAL_MACHINE
            }
        CredApi.INSTANCE.CredWriteW(credential, 0)
    }

    internal fun deleteTarget(target: String) {
        try {
            CredApi.INSTANCE.CredDeleteW(target, CRED_TYPE_GENERIC, 0)
        } catch (e: LastErrorException) {
            if (e.errorCode != ERROR_NOT_FOUND) throw e
        }
    }

    /** The CREDENTIALW structure from wincred.h. Field names match the Win32 definition. */
    @Suppress("PropertyName", "ktlint:standard:property-naming")
    @Structure.FieldOrder(
        "Flags",
        "Type",
        "TargetName",
        "Comment",
        "LastWritten",
        "CredentialBlobSize",
        "CredentialBlob",
        "Persist",
        "AttributeCount",
        "Attributes",
        "TargetAlias",
        "UserName",
    )
    class Credential(
        pointer: Pointer? = null,
    ) : Structure(pointer, ALIGN_DEFAULT, W32APITypeMapper.UNICODE) {
        @JvmField var Flags: Int = 0

        @JvmField var Type: Int = 0

        @JvmField var TargetName: String? = null

        @JvmField var Comment: String? = null

        @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()

        @JvmField var CredentialBlobSize: Int = 0

        @JvmField var CredentialBlob: Pointer? = null

        @JvmField var Persist: Int = 0

        @JvmField var AttributeCount: Int = 0

        @JvmField var Attributes: Pointer? = null

        @JvmField var TargetAlias: String? = null

        @JvmField var UserName: String? = null
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface CredApi : StdCallLibrary {
        @Throws(LastErrorException::class)
        fun CredReadW(
            targetName: String,
            type: Int,
            flags: Int,
            credential: PointerByReference,
        ): Boolean

        @Throws(LastErrorException::class)
        fun CredWriteW(
            credential: Credential,
            flags: Int,
        ): Boolean

        @Throws(LastErrorException::class)
        fun CredDeleteW(
            targetName: String,
            type: Int,
            flags: Int,
        ): Boolean

        fun CredFree(buffer: Pointer)

        companion object {
            // Only the type mapper (Java String <-> wide string); the W-suffixed names are spelled out.
            val INSTANCE: CredApi =
                Native.load(
                    "Advapi32",
                    CredApi::class.java,
                    mapOf(Library.OPTION_TYPE_MAPPER to W32APITypeMapper.UNICODE),
                )
        }
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val ERROR_NOT_FOUND = 1168
    }
}
