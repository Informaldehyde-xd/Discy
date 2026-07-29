package com.example.discconverter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

enum class ConversionType {
    BIN_TO_ISO,
    ISO_TO_ZSO,
    ZSO_TO_ISO
}

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

        contentResolver.openInputStream(inputUri)?.buffered(65536)?.use { rawInput ->
            val input = DataInputStream(rawInput)
            contentResolver.openOutputStream(outputUri)?.buffered(65536)?.use { output ->
                val buffer = ByteArray(BIN_SECTOR_SIZE)
                
                try {
                    while (true) {
                        input.readFully(buffer)
                        
                        val mode = buffer[15].toInt()
                        val offset = if (mode == 2) 24 else 16
                        output.write(buffer, offset, DEFAULT_BLOCK_SIZE)

                        processedSectors++
                        if (sectors > 0 && processedSectors % 1000L == 0L) {
                            onProgress(processedSectors.toFloat() / sectors.toFloat())
                        }
                    }
                } catch (e: EOFException) {
                    // Reached the end of the file safely
                }
            }
        }
        onProgress(1f)
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

        // Calculate index shift for 2GB+ support
        var indexShift = 0
        while ((totalBytes shr indexShift) > 0x7FFFFFFF) {
            indexShift++
        }
        val align = 1 shl indexShift

        val totalBlocks = ((totalBytes + DEFAULT_BLOCK_SIZE - 1) / DEFAULT_BLOCK_SIZE).toInt()
        val indexTable = IntArray(totalBlocks + 1)
        
        var currentOffset = 24L + (totalBlocks + 1) * 4L
        if (currentOffset % align != 0L) {
            currentOffset += align - (currentOffset % align)
        }

        indexTable[0] = (currentOffset shr indexShift).toInt()

        val deflater = Deflater(compressionLevel)

        val outFd = contentResolver.openFileDescriptor(outputUri, "rw")
            ?: throw IllegalStateException("Cannot open output stream in read-write mode")

        outFd.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { output ->
                output.write(ByteArray(currentOffset.toInt()))

                contentResolver.openInputStream(inputUri)?.buffered(65536)?.use { input ->
                    val rawBuffer = ByteArray(DEFAULT_BLOCK_SIZE)
                    val compressBuffer = ByteArray(DEFAULT_BLOCK_SIZE * 2)

                    for (i in 0 until totalBlocks) {
                        var bytesRead = 0
                        while (bytesRead < DEFAULT_BLOCK_SIZE) {
                            val r = input.read(rawBuffer, bytesRead, DEFAULT_BLOCK_SIZE - bytesRead)
                            if (r == -1) break
                            bytesRead += r
                        }

                        if (bytesRead < DEFAULT_BLOCK_SIZE) {
                            rawBuffer.fill(0, bytesRead, DEFAULT_BLOCK_SIZE)
                        }

                        deflater.setInput(rawBuffer)
                        deflater.finish()
                        val compressedSize = deflater.deflate(compressBuffer)
                        deflater.reset()

                        if (compressedSize >= DEFAULT_BLOCK_SIZE) {
                            output.write(rawBuffer)
                            currentOffset += DEFAULT_BLOCK_SIZE
                            
                            val rem = currentOffset % align
                            if (rem != 0L) {
                                val pad = align - rem
                                output.write(ByteArray(pad.toInt()))
                                currentOffset += pad
                            }
                            
                            // FIX: Flag the current block as uncompressed natively
                            indexTable[i] = indexTable[i] or 0x80000000.toInt()
                            indexTable[i + 1] = (currentOffset shr indexShift).toInt()
                        } else {
                            output.write(compressBuffer, 0, compressedSize)
                            currentOffset += compressedSize
                            
                            val rem = currentOffset % align
                            if (rem != 0L) {
                                val pad = align - rem
                                output.write(ByteArray(pad.toInt()))
                                currentOffset += pad
                            }

                            indexTable[i + 1] = (currentOffset shr indexShift).toInt()
                        }

                        if (i % 1000 == 0 || i == totalBlocks - 1) {
                            onProgress((i + 1).toFloat() / totalBlocks.toFloat())
                        }
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
                    put(1.toByte())
                    put(indexShift.toByte())
                    putShort(0)
                }.array()

                channel.write(ByteBuffer.wrap(header))

                val indexBytes = ByteBuffer.allocate((totalBlocks + 1) * 4).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    indexTable.forEach { putInt(it) }
                }.array()

                channel.write(ByteBuffer.wrap(indexBytes))
            }
        }
        onProgress(1f)
    }

    suspend fun zsoToIso(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        onProgress: (Float) -> Unit
    ) {
        val contentResolver = context.contentResolver

        contentResolver.openInputStream(inputUri)?.buffered(65536)?.use { rawInput ->
            val input = DataInputStream(rawInput)
            val headerBuffer = ByteArray(24)
            input.readFully(headerBuffer)

            val header = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            header.get(magic)

            if (!magic.contentEquals(ZSO_MAGIC)) {
                throw IllegalArgumentException("Invalid ZSO file header.")
            }

            header.getInt()
            val totalBytes = header.getLong()
            val blockSize = header.getInt()
            header.get()
            val indexShift = header.get().toInt()
            header.getShort()

            val totalBlocks = ((totalBytes + blockSize - 1) / blockSize).toInt()

            val indexBytes = ByteArray((totalBlocks + 1) * 4)
            input.readFully(indexBytes)
            val indexTable = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).run {
                IntArray(totalBlocks + 1) { getInt() }
            }

            var currentReadPos = 24L + indexBytes.size

            contentResolver.openOutputStream(outputUri)?.buffered(65536)?.use { output ->
                val inflater = Inflater()

                for (i in 0 until totalBlocks) {
                    val rawOffset = indexTable[i]
                    val nextRawOffset = indexTable[i + 1]

                    val isUncompressed = (rawOffset and 0x80000000.toInt()) != 0
                    val offset = (rawOffset and 0x7FFFFFFF).toLong() shl indexShift
                    val nextOffset = (nextRawOffset and 0x7FFFFFFF).toLong() shl indexShift
                    val compressedLen = (nextOffset - offset).toInt()

                    // Skip formatting pad adjustments
                    if (offset > currentReadPos) {
                        var skipAmt = (offset - currentReadPos).toInt()
                        while (skipAmt > 0) {
                            val s = input.skipBytes(skipAmt)
                            if (s <= 0) break
                            skipAmt -= s
                        }
                        currentReadPos = offset
                    }

                    val blockData = ByteArray(compressedLen)
                    input.readFully(blockData)
                    currentReadPos += compressedLen

                    if (isUncompressed) {
                        output.write(blockData)
                    } else {
                        inflater.setInput(blockData)
                        val decompressed = ByteArray(blockSize)
                        var decompressedSize = 0
                        
                        try {
                            decompressedSize = inflater.inflate(decompressed)
                        } catch (e: Exception) {
                            throw RuntimeException("Decompression failed at block $i: ${e.message}", e)
                        }
                        
                        inflater.reset()
                        output.write(decompressed, 0, decompressedSize)
                    }

                    if (i % 1000 == 0 || i == totalBlocks - 1) {
                        onProgress((i + 1).toFloat() / totalBlocks.toFloat())
                    }
                }
                inflater.end()
            }
        }
        onProgress(1f)
    }
}
