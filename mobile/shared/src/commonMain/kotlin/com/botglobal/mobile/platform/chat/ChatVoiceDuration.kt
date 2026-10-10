package com.botglobal.mobile.platform.chat

/** Finalized AAC-LC media time, not recorder elapsed time or an Android metadata estimate.
 * Mirrors ChatAacContainer: ceil(sum(stts.count * stts.delta) * 1000 / mdhd.timescale).
 * This bounded container preflight does not replace the server's AAC payload decoder. */
object ChatVoiceDuration {
    const val MaxBytes = 10 * 1024 * 1024
    fun milliseconds(bytes: ByteArray): Int? = try {
        require(bytes.size in 1..MaxBytes)
        Container(bytes).inspect()
    } catch (_: IllegalArgumentException) { null }
      catch (_: IndexOutOfBoundsException) { null }

    private data class Box(val type: String, val start: Int, val end: Int) {
        fun need(length: Int) { require(end - start >= length) }
    }
    private class Container(val data: ByteArray) {
        fun u16(p: Int) = (byte(p) shl 8) or byte(p + 1)
        fun byte(p: Int) = data[p].toInt() and 255
        fun u32(p: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or byte(p + i).toLong() }
        // Every accepted 64-bit field is bounded by file length or five minutes below.
        fun u64(p: Int): Long { require(u32(p) <= 0x7fffffff); return (u32(p) shl 32) or u32(p + 4) }
        fun name(p: Int) = (0..3).map { byte(p + it).toChar() }.joinToString("")
        fun boxes(from: Int, end: Int): List<Box> {
            val result = mutableListOf<Box>(); var p = from
            require(from >= 0 && end <= data.size && from <= end)
            while (p < end) {
                require(end - p >= 8 && result.size < 10_000)
                var size = u32(p); var header = 8
                if (size == 1L) { require(end - p >= 16); size = u64(p + 8); header = 16 }
                if (size == 0L) size = (end - p).toLong()
                require(size >= header && size <= end - p)
                result += Box(name(p + 4), p + header, p + size.toInt()); p += size.toInt()
            }
            return result
        }
        fun children(box: Box) = boxes(box.start, box.end)
        fun one(boxes: List<Box>, type: String): Box = requireNotNull(boxes.singleOrNull { it.type == type })
        fun count(box: Box, width: Int): Int {
            box.need(8); val n = u32(box.start + 4)
            require(n <= 100_000 && (box.end - box.start).toLong() == 8L + n * width)
            return n.toInt()
        }
        fun descriptor(from: Int, end: Int, tag: Int): Box {
            var p = from; require(p < end && byte(p++) == tag)
            var length = 0; var finished = false
            for (i in 0..3) {
                require(p < end); val b = byte(p++); length = length * 128 + (b and 127)
                if (b and 128 == 0) { finished = true; break }
            }
            require(finished && length <= end - p)
            return Box("descriptor", p, p + length)
        }
        fun sampleRate(box: Box): Long {
            box.need(8); val es = descriptor(box.start + 4, box.end, 3); es.need(3)
            var p = es.start + 2; val flags = byte(p++)
            require(flags and 0x40 == 0)
            if (flags and 0x80 != 0) p += 2
            if (flags and 0x20 != 0) p += 2
            val decoder = descriptor(p, es.end, 4); decoder.need(15)
            require(byte(decoder.start) == 0x40 && byte(decoder.start + 1) shr 2 == 5)
            val config = descriptor(decoder.start + 13, decoder.end, 5); config.need(2)
            val bits = u16(config.start); val rate = (bits shr 7) and 15; val channels = (bits shr 3) and 15
            require(bits shr 11 == 2 && rate <= 12 && channels in 1..2 && bits and 4 == 0)
            return intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)[rate].toLong()
        }
        fun inspect(): Int {
            val top = boxes(0, data.size); one(top, "ftyp").need(8)
            val media = top.filter { it.type == "mdat" }
            require(media.isNotEmpty() && top.none { it.type == "moof" })
            val track = one(children(one(top, "moov")), "trak")
            val mdia = children(one(children(track), "mdia"))
            val handler = one(mdia, "hdlr"); handler.need(12); require(name(handler.start + 8) == "soun")
            val header = one(mdia, "mdhd"); header.need(24)
            val version = byte(header.start); require(version <= 1); header.need(if (version == 1) 36 else 24)
            val scale = u32(header.start + if (version == 1) 20 else 12)
            val duration = if (version == 1) u64(header.start + 24) else u32(header.start + 16)
            require(scale > 0 && duration > 0 && duration <= scale * 300)
            val minf = children(one(mdia, "minf"))
            val dref = one(children(one(minf, "dinf")), "dref"); dref.need(8)
            val references = boxes(dref.start + 8, dref.end)
            require(u32(dref.start + 4) == 1L && references.size == 1)
            val reference = references.single()
            require(reference.type == "url " && reference.end - reference.start == 4 && u32(reference.start) == 1L)
            val table = children(one(minf, "stbl")); val stsd = one(table, "stsd"); stsd.need(8)
            require(u32(stsd.start + 4) == 1L)
            val sample = one(boxes(stsd.start + 8, stsd.end), "mp4a"); sample.need(28)
            require(u16(sample.start + 8) == 0 && u16(sample.start + 16) in 1..2)
            val rate = sampleRate(one(boxes(sample.start + 28, sample.end), "esds"))
            require(u32(sample.start + 24) shr 16 == rate)
            val stts = one(table, "stts"); val times = count(stts, 8)
            var samples = 0L; var measured = 0L
            repeat(times) { i ->
                val n = u32(stts.start + 8 + i * 8); val delta = u32(stts.start + 12 + i * 8)
                require(n > 0 && delta > 0)
                samples += n; measured += n * delta
                require(samples <= 100_000 && measured <= scale * 300)
            }
            val tolerance = maxOf(1L, (scale + 19) / 20)
            require(samples > 0 && measured > 0 && kotlin.math.abs(duration - measured) <= tolerance)
            val stsz = one(table, "stsz"); stsz.need(12)
            val fixed = u32(stsz.start + 4); val sizeCount = u32(stsz.start + 8)
            require(sizeCount == samples && (fixed != 0L || (stsz.end - stsz.start).toLong() == 12 + sizeCount * 4))
            val sizes = LongArray(sizeCount.toInt()) { i ->
                (if (fixed == 0L) u32(stsz.start + 12 + i * 4) else fixed).also { require(it in 1..8192) }
            }
            val offsets = requireNotNull(table.singleOrNull { it.type == "stco" || it.type == "co64" })
            val width = if (offsets.type == "stco") 4 else 8; val chunks = count(offsets, width)
            val stsc = one(table, "stsc"); val mappings = count(stsc, 12)
            require(chunks > 0 && mappings > 0 && u32(stsc.start + 8) == 1L)
            repeat(mappings) { i ->
                val start = u32(stsc.start + 8 + i * 12)
                require(start <= chunks && (i == 0 || start > u32(stsc.start + 8 + (i - 1) * 12)))
                require(u32(stsc.start + 12 + i * 12) > 0 && u32(stsc.start + 16 + i * 12) == 1L)
            }
            var sampleIndex = 0; var mapping = 0; var previousEnd = 0L
            for (chunk in 1..chunks) {
                while (mapping + 1 < mappings && u32(stsc.start + 8 + (mapping + 1) * 12) <= chunk) mapping++
                val p = offsets.start + 8 + (chunk - 1) * width
                val offset = if (width == 4) u32(p) else u64(p)
                val n = u32(stsc.start + 12 + mapping * 12)
                require(n <= sizes.size - sampleIndex)
                var length = 0L; repeat(n.toInt()) { length += sizes[sampleIndex++] }
                require(offset >= previousEnd && media.any { offset >= it.start && offset <= it.end.toLong() - length })
                previousEnd = offset + length
            }
            require(sampleIndex == sizes.size)
            return ((measured * 1000 + scale - 1) / scale).toInt()
        }
    }
}
