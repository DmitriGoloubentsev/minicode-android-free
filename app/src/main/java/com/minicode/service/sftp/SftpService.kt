package com.minicode.service.sftp

import android.util.Log
import com.minicode.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

private const val TAG = "SftpService"

class SftpService(private val session: ClientSession) {

    private var sftpClient: SftpClient? = null

    private fun getClient(): SftpClient {
        val existing = sftpClient
        if (existing != null && existing.isOpen) return existing
        val client = SftpClientFactory.instance().createSftpClient(session)
        sftpClient = client
        return client
    }

    suspend fun listDirectory(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
            val entries = client.readDir(path)
            entries
                .filter { it.filename != "." && it.filename != ".." }
                .map { entry ->
                    val attrs = entry.attributes
                    val isDir = attrs.isDirectory
                    val isLink = attrs.isSymbolicLink
                    val fullPath = if (path == "/") "/${ entry.filename}" else "$path/${entry.filename}"
                    FileNode(
                        name = entry.filename,
                        path = fullPath,
                        isDirectory = isDir,
                        size = attrs.size,
                        modifiedTime = attrs.modifyTime?.toMillis() ?: 0L,
                        isSymlink = isLink,
                    )
                }
                .sortedWith(compareBy<FileNode> { !it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list directory: $path", e)
            throw e
        }
    }

    suspend fun readFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
            val handle = client.open(path, setOf(SftpClient.OpenMode.Read))
            handle.use { h ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32768)
                var offset = 0L
                while (true) {
                    val n = client.read(h, offset, buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    offset += n
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: $path", e)
            throw e
        }
    }

    suspend fun writeFile(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
            val handle = client.open(
                path,
                setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)
            )
            handle.use { h ->
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(32768, data.size - offset)
                    client.write(h, offset.toLong(), data, offset, len)
                    offset += len
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file: $path", e)
            throw e
        }
    }

    suspend fun writeFileStream(
        path: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
            val handle = client.open(
                path,
                setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)
            )
            handle.use { h ->
                val buf = ByteArray(32768)
                var offset = 0L
                while (true) {
                    val n = inputStream.read(buf)
                    if (n <= 0) break
                    client.write(h, offset, buf, 0, n)
                    offset += n
                    onProgress(offset, totalSize)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file stream: $path", e)
            throw e
        }
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        try {
            getClient().mkdir(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mkdir: $path", e)
            throw e
        }
    }

    suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
            if (isDirectory) {
                deleteRecursive(client, path)
            } else {
                client.remove(path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete: $path", e)
            throw e
        }
    }

    private fun deleteRecursive(client: SftpClient, path: String) {
        val entries = client.readDir(path)
        for (entry in entries) {
            if (entry.filename == "." || entry.filename == "..") continue
            val childPath = "$path/${entry.filename}"
            if (entry.attributes.isDirectory) {
                deleteRecursive(client, childPath)
            } else {
                client.remove(childPath)
            }
        }
        client.rmdir(path)
    }

    suspend fun rename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        try {
            getClient().rename(oldPath, newPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename: $oldPath -> $newPath", e)
            throw e
        }
    }

    suspend fun stat(path: String): SftpClient.Attributes = withContext(Dispatchers.IO) {
        getClient().stat(path)
    }

    suspend fun getHomeDirectory(): String = withContext(Dispatchers.IO) {
        try {
            val client = getClient()
            client.canonicalPath(".")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get home directory", e)
            "/"
        }
    }

    fun close() {
        try {
            sftpClient?.close()
        } catch (_: Exception) {}
        sftpClient = null
    }
}
