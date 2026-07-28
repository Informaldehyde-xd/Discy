package com.example.discconverter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

enum class ConversionType { BIN_TO_ISO, ISO_TO_ZSO, ZSO_TO_ISO }

object DiscConverter {

    private const val DEFAULT_BLOCK_SIZE = 2048
    private const val BIN_SECTOR_SIZE = 2352
    private val ZSO_MAGIC = byteArrayOf('Z'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 0x01)

    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown_file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun deriveOutputName(inputName: String, mode: ConversionType): String {
        val baseName = inputName.substringBeforeLast('.')
        return when (mode) {
            ConversionType.BIN_TO_ISO -> "$baseName.iso"
            ConversionType.ISO_TO_ZSO -> "$baseName.zso"
            ConversionType.ZSO_TO_ISO -> "$baseName.iso"
        }
    }

    suspend fun binToIso(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        onProgress: (Float) -> Unit
    ) {
        val contentResolver = context.contentResolver
        val fileDescriptor = contentResolver.openFileDescriptor(inputUri, "r") ?: return
        val totalSize = fileDescriptor.statSize
        fileDescriptor.close()

        val sectors = totalSize / BIN_SECTOR_SIZE
        var processedSectors = 0L

        contentResolver.openInputStream(inputUri)?.use { input ->
            contentResolver.openOutputStream(outputUri)?.use { output ->
                val buffer = ByteArray(BIN_SECTOR_SIZE)
                while (input.read(buffer) == BIN_SECTOR_SIZE) {
                    val mode = buffer[15].toInt()
                    val offset = if (mode == 2) 24 else 16
                    output.write(buffer, offset, DEFAULT_BLOCK_SIZE)

                    processedSectors++
                    if (sectors > 0) {
                        onProgress(processedSectors.toFloat() / sectors.toFloat())
                    }
                }
            }
        }
    }

    suspend fun isoToZso(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        compressionLevel: Int = 6,
        onProgress: (Float) -> Unit
    ) {
        val contentResolver = context.contentResolver
        val fileDescriptor = contentResolver.openFileDescriptor(inputUri, "r") ?: return
        val totalBytes = fileDescriptor.statSize
        fileDescriptor.close()

        val totalBlocks = ((totalBytes + DEFAULT_BLOCK_SIZE - 1) / DEFAULT_BLOCK_SIZE).toInt()
        val indexTable = IntArray(totalBlocks + 1)
        var currentOffset = 24 + (totalBlocks + 1) * 4

        indexTable[0] = currentOffset

        val deflater = Deflater(compressionLevel)

        val outFd = contentResolver.openFileDescriptor(outputUri, "rw")
            ?: throw IllegalStateException("Cannot open output stream in read-write mode")

        outFd.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { output ->
                output.write(ByteArray(currentOffset))

                contentResolver.openInputStream(inputUri)?.use { input ->
                    val rawBuffer = ByteArray(DEFAULT_BLOCK_SIZE)
                    val compressBuffer = ByteArray(DEFAULT_BLOCK_SIZE * 2)

                    for (i in 0 until totalBlocks) {
                        val bytesRead = input.read(rawBuffer)
                        if (bytesRead < DEFAULT_BLOCK_SIZE) {
                            rawBuffer.fill(0, bytesRead.coerceAtLeast(0), DEFAULT_BLOCK_SIZE)
                        }

                        deflater.setInput(rawBuffer)
                        deflater.finish()
                        val compressedSize = deflater.deflate(compressBuffer)
                        deflater.reset()

                        if (compressedSize >= DEFAULT_BLOCK_SIZE) {
                            output.write(rawBuffer)
                            currentOffset += DEFAULT_BLOCK_SIZE
                            indexTable[i + 1] = (currentOffset or 0x80000000.toInt())
                        } else {
                            output.write(compressBuffer, 0, compressedSize)
                            currentOffset += compressedSize
                            indexTable[i + 1] = currentOffset
                        }

                        onProgress((i + 1).toFloat() / totalBlocks.toFloat())
                    }
                }
                deflater.end()

                val channel = output.channel
                channel.position(0)

                val header = ByteBuffer.allocate(24).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    put(ZSO_MAGIC)
                    putInt(24)
                    putLong(totalBytes)
                    putInt(DEFAULT_BLOCK_SIZE)
                    put(0.toByte())
                    put(byteArrayOf(0, 0, 0))
                }.array()

                channel.write(ByteBuffer.wrap(header))

                val indexBytes = ByteBuffer.allocate((totalBlocks + 1) * 4).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    indexTable.forEach { putInt(it) }
                }.array()

                channel.write(ByteBuffer.wrap(indexBytes))
            }
        }
    }

    suspend fun zsoToIso(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        onProgress: (Float) -> Unit
    ) {
        val contentResolver = context.contentResolver

        contentResolver.openInputStream(inputUri)?.use { input ->
            val headerBuffer = ByteArray(24)
            input.read(headerBuffer)

            val header = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            header.get(magic)

            if (!magic.contentEquals(ZSO_MAGIC)) {
                throw IllegalArgumentException("Invalid ZSO file header.")
            }

            header.getInt()
            val totalBytes = header.getLong()
            val blockSize = header.getInt()
            val totalBlocks = ((totalBytes + blockSize - 1) / blockSize).toInt()

            val indexBytes = ByteArray((totalBlocks + 1) * 4)
            input.read(indexBytes)
            val indexTable = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).run {
                IntArray(totalBlocks + 1) { getInt() }
            }

            contentResolver.openOutputStream(outputUri)?.use { output ->
                val inflater = Inflater()

                for (i in 0 until totalBlocks) {
                    val rawOffset = indexTable[i]
                    val nextRawOffset = indexTable[i + 1]

                    val isUncompressed = (rawOffset and 0x80000000.toInt()) != 0
                    val offset = rawOffset and 0x7FFFFFFF
                    val nextOffset = nextRawOffset and 0x7FFFFFFF
                    val compressedLen = nextOffset - offset

                    val blockData = ByteArray(compressedLen)
                    input.read(blockData)

                    if (isUncompressed) {
                        output.write(blockData)
                    } else {
                        inflater.setInput(blockData)
                        val decompressed = ByteArray(blockSize)
                        inflater.inflate(decompressed)
                        inflater.reset()
                        output.write(decompressed)
                    }

                    onProgress((i + 1).toFloat() / totalBlocks.toFloat())
                }
                inflater.end()
            }
        }
    }
}
