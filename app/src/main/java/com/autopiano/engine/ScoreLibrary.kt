package com.autopiano.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID

data class LibraryScore(
    val id: String,
    val name: String,
    val isMidi: Boolean,
    val sizeBytes: Long
)

object ScoreLibrary {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val DIRECTORY = "score_library"

    @Synchronized
    fun list(context: Context): List<LibraryScore> {
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw IOException("无法读取本地曲库")
        return files.asSequence()
            .filter { it.isFile && it.extension == "json" && isValidId(it.nameWithoutExtension) }
            .mapNotNull { file ->
                try {
                    val score = readMetadata(directory, file.nameWithoutExtension)
                    val data = File(directory, "${score.id}.score")
                    score.takeIf { data.isFile && data.length() == score.sizeBytes }
                } catch (_: Exception) {
                    null
                }
            }
            .sortedWith(compareBy<LibraryScore> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
            .toList()
    }

    @Synchronized
    fun import(context: Context, uri: Uri): LibraryScore {
        val resolver = context.contentResolver
        var name: String? = null
        var declaredSize: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    name = cursor.getString(nameColumn)
                }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    declaredSize = cursor.getLong(sizeColumn)
                }
            }
        }
        val displayName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: throw IOException("无法获取文件名")
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in setOf("mid", "midi", "txt")) {
            "仅支持 .mid、.midi 和 .txt 文件"
        }
        require((declaredSize ?: 0L) <= MAX_BYTES.toLong()) { "文件超过 10 MiB 上限" }
        val bytes = (resolver.openInputStream(uri) ?: throw IOException("无法打开文件"))
            .use { readLimited(it) }
        val isMidi = validateFormat(displayName, bytes)
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建本地曲库")

        for (existing in list(context)) {
            if (existing.name != displayName || existing.sizeBytes != bytes.size.toLong()) continue
            val sameContent = try {
                File(directory, "${existing.id}.score").inputStream().use {
                    readLimited(it).contentEquals(bytes)
                }
            } catch (_: IOException) {
                false
            }
            if (sameContent) return existing
        }

        val score = LibraryScore(UUID.randomUUID().toString(), displayName, isMidi, bytes.size.toLong())
        val dataFile = File(directory, "${score.id}.score")
        val metadata = AtomicFile(File(directory, "${score.id}.json"))
        try {
            dataFile.outputStream().use { it.write(bytes) }
            val json = JSONObject()
                .put("id", score.id)
                .put("name", score.name)
                .put("isMidi", score.isMidi)
                .put("sizeBytes", score.sizeBytes)
            val output = metadata.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                metadata.finishWrite(output)
            } catch (error: Exception) {
                metadata.failWrite(output)
                throw error
            }
        } catch (error: Exception) {
            dataFile.delete()
            metadata.delete()
            throw error
        }
        return score
    }

    @Synchronized
    fun read(context: Context, id: String): Pair<LibraryScore, ByteArray> {
        require(isValidId(id)) { "无效的曲库 ID" }
        val directory = File(context.filesDir, DIRECTORY)
        val score = readMetadata(directory, id)
        val dataFile = File(directory, "$id.score")
        if (!dataFile.isFile) throw IOException("曲谱已删除或文件不存在")
        val bytes = dataFile.inputStream().use { readLimited(it) }
        if (bytes.size.toLong() != score.sizeBytes) throw IOException("曲谱文件大小不匹配")
        if (validateFormat(score.name, bytes) != score.isMidi) throw IOException("曲谱格式不匹配")
        return score to bytes
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        require(isValidId(id)) { "无效的曲库 ID" }
        val directory = File(context.filesDir, DIRECTORY)
        val dataFile = File(directory, "$id.score")
        val metadataFile = File(directory, "$id.json")
        val existed = dataFile.exists() || metadataFile.exists()
        if (!existed) return false
        if (dataFile.exists() && !dataFile.delete()) return false
        AtomicFile(metadataFile).delete()
        return !dataFile.exists() && !metadataFile.exists()
    }

    private fun readMetadata(directory: File, id: String): LibraryScore {
        require(isValidId(id)) { "无效的曲库 ID" }
        val json = JSONObject(String(AtomicFile(File(directory, "$id.json")).readFully(), Charsets.UTF_8))
        val score = LibraryScore(
            json.getString("id"),
            json.getString("name"),
            json.getBoolean("isMidi"),
            json.getLong("sizeBytes")
        )
        require(score.id == id && score.name.isNotBlank() && score.sizeBytes in 1L..MAX_BYTES.toLong()) {
            "曲库元数据损坏"
        }
        return score
    }

    private fun isValidId(id: String): Boolean = try {
        UUID.fromString(id).toString() == id
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > MAX_BYTES - output.size()) throw IOException("文件超过 10 MiB 上限")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateFormat(name: String, bytes: ByteArray): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in setOf("mid", "midi", "txt")) {
            "仅支持 .mid、.midi 和 .txt 文件"
        }
        require(bytes.isNotEmpty()) { "文件为空" }
        val hasMidiHeader = bytes.size >= 4 &&
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'T'.code.toByte() &&
            bytes[2] == 'h'.code.toByte() && bytes[3] == 'd'.code.toByte()
        if (hasMidiHeader) {
            require(bytes.size >= 14) { "MIDI 文件头不完整" }
            val headerLength = ByteBuffer.wrap(bytes, 4, 4).int
            require(headerLength >= 6 && headerLength <= bytes.size - 8) { "MIDI 文件头损坏" }
            return true
        }
        require(extension == "txt") { "无效的 MIDI 文件头，应以 MThd 开始" }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("TXT 文件必须使用 UTF-8 编码")
        }
        require(text.removePrefix("\uFEFF").isNotBlank()) { "TXT 文件没有内容" }
        require(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "TXT 文件包含二进制或控制字符"
        }
        return false
    }
}
