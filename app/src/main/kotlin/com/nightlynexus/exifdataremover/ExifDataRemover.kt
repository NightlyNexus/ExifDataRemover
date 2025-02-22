package com.nightlynexus.exifdataremover

import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import java.io.IOException

@Throws(IOException::class)
fun copyWithoutExifData(
  theSource: BufferedSource,
  theSink: BufferedSink
): Boolean {
  theSink
    .use { sink ->
      theSource
        .use { source ->
          if (source.rangeEquals(0, jpegFileStart)) {
            sink.write(source, 2)
            val sourceBuffer = source.buffer
            while (true) {
              source.require(2)
              if (sourceBuffer[0] != marker) {
                throw IOException("${sourceBuffer[0]} != $marker")
              }
              val nextByte = sourceBuffer[1]
              if (nextByte == APP1 || nextByte == comment) {
                source.skip(2)
                val size = source.readShort().toUnsignedInt()
                source.skip((size - 2).toLong())
              } else if (nextByte == startOfStream) {
                sink.writeAll(source)
                break
              } else {
                sink.write(source, 2)
                val size = source.readShort().toUnsignedInt()
                sink.writeShort(size)
                sink.write(source, (size - 2).toLong())
              }
            }
            return true
          } else {
            return false
          }
        }
    }
}

private fun Short.toUnsignedInt(): Int {
  return toInt() and 0xffff
}

private val jpegFileStart = "FFD8".decodeHex()
private val marker = 0xFF.toByte()
private val APP1 = 0xE1.toByte()
private val comment = 0xFE.toByte()
private val startOfStream = 0xDA.toByte()
