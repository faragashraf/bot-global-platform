using System.Buffers.Binary;
using System.Text;

namespace BotGlobal.Communication.Application.Chat;

// Cheap bounded ISO-BMFF AAC-LC preflight, followed by ChatAacDecoder before publication.
// Fragmented, encrypted, video and multi-track containers are deliberately unsupported.
internal static class ChatAacContainer
{
    private readonly record struct Box(string Type, int Start, int End);
    public static int Validate(byte[] bytes)
    {
        try { return Inspect(bytes); }
        catch (Exception error) when (error is ArgumentException or OverflowException or InvalidOperationException or IndexOutOfRangeException)
        { throw new InvalidDataException("chat_voice_container_invalid"); }
    }

    private static int Inspect(byte[] data)
    {
        var top = Boxes(data, 0, data.Length);
        var ftyp = One(top, "ftyp");
        if (ftyp.End - ftyp.Start < 8) throw Invalid();
        var moov = One(top, "moov");
        var media = top.Where(x => x.Type == "mdat").ToArray();
        if (media.Length == 0 || top.Any(x => x.Type == "moof")) throw Invalid();
        var tracks = Children(data, moov).Where(x => x.Type == "trak").ToArray();
        if (tracks.Length != 1) throw Invalid();
        var mdia = One(Children(data, tracks[0]), "mdia");
        var mdiaChildren = Children(data, mdia);
        var handler = One(mdiaChildren, "hdlr");
        Need(handler, 12);
        if (Encoding.ASCII.GetString(data, handler.Start + 8, 4) != "soun") throw Invalid();
        var header = One(mdiaChildren, "mdhd");
        Need(header, 24);
        var version = data[header.Start];
        if (version > 1) throw Invalid();
        Need(header, version == 1 ? 36 : 24);
        var timescale = U32(data, header.Start + (version == 1 ? 20 : 12));
        var duration = version == 1 ? U64(data, header.Start + 24) : U32(data, header.Start + 16);
        if (timescale == 0 || duration == 0 || duration > (ulong)timescale * 300) throw Invalid();
        var minf = One(mdiaChildren, "minf");
        var dref = One(Children(data, One(Children(data, minf), "dinf")), "dref");
        Need(dref, 8);
        var references = Boxes(data, dref.Start + 8, dref.End);
        if (U32(data, dref.Start + 4) != 1 || references.Count != 1 ||
            references[0].Type != "url " || references[0].End - references[0].Start != 4 ||
            U32(data, references[0].Start) != 1) throw Invalid(); // Self-contained media only.
        var table = Children(data, One(Children(data, minf), "stbl"));
        var stsd = One(table, "stsd"); Need(stsd, 8);
        if (U32(data, stsd.Start + 4) != 1) throw Invalid();
        var sample = One(Boxes(data, stsd.Start + 8, stsd.End), "mp4a"); Need(sample, 28);
        if (U16(data, sample.Start + 8) != 0 || U16(data, sample.Start + 16) is not (1 or 2)) throw Invalid();
        var esds = One(Boxes(data, sample.Start + 28, sample.End), "esds");
        var sampleRate = ValidateDescriptor(data, esds);
        if ((U32(data, sample.Start + 24) >> 16) != sampleRate) throw Invalid();

        var stts = One(table, "stts"); Need(stts, 8);
        var timeCount = Count(data, stts, 8);
        ulong sampleCount = 0, measured = 0;
        for (var i = 0; i < timeCount; i++)
        {
            var count = U32(data, stts.Start + 8 + i * 8); var delta = U32(data, stts.Start + 12 + i * 8);
            if (count == 0 || delta == 0) throw Invalid();
            sampleCount = checked(sampleCount + count); measured = checked(measured + (ulong)count * delta);
        }
        var tolerance = Math.Max(1UL, ((ulong)timescale + 19) / 20);
        if (sampleCount == 0 || sampleCount > 100_000 || measured == 0 || measured > (ulong)timescale * 300 ||
            (duration > measured ? duration - measured : measured - duration) > tolerance) throw Invalid();
        var stsz = One(table, "stsz"); Need(stsz, 12);
        var fixedSize = U32(data, stsz.Start + 4); var sizeCount = U32(data, stsz.Start + 8);
        if (sizeCount != sampleCount || (fixedSize == 0 && stsz.End - stsz.Start != 12 + sizeCount * 4)) throw Invalid();
        var sizes = new uint[(int)sizeCount];
        for (var i = 0; i < sizes.Length; i++) { sizes[i] = fixedSize == 0 ? U32(data, stsz.Start + 12 + i * 4) : fixedSize; if (sizes[i] is 0 or > 8192) throw Invalid(); }
        var offsets = table.Where(x => x.Type is "stco" or "co64").ToArray();
        if (offsets.Length != 1) throw Invalid();
        var stco = offsets[0]; var width = stco.Type == "stco" ? 4 : 8; Need(stco, 8);
        var chunks = Count(data, stco, width);
        var stsc = One(table, "stsc"); Need(stsc, 8); var mappingCount = Count(data, stsc, 12);
        if (chunks == 0 || mappingCount == 0 || U32(data, stsc.Start + 8) != 1) throw Invalid();
        for (var i = 0; i < mappingCount; i++)
        {
            var start = U32(data, stsc.Start + 8 + i * 12);
            if (start > chunks || (i > 0 && start <= U32(data, stsc.Start + 8 + (i - 1) * 12)) ||
                U32(data, stsc.Start + 12 + i * 12) == 0 || U32(data, stsc.Start + 16 + i * 12) != 1) throw Invalid();
        }
        var sampleIndex = 0; var mapping = 0; ulong previousEnd = 0;
        for (var chunk = 1; chunk <= chunks; chunk++)
        {
            while (mapping + 1 < mappingCount && U32(data, stsc.Start + 8 + (mapping + 1) * 12) <= chunk) mapping++;
            var offset = width == 4 ? U32(data, stco.Start + 8 + (chunk - 1) * width) : U64(data, stco.Start + 8 + (chunk - 1) * width);
            var count = U32(data, stsc.Start + 12 + mapping * 12);
            ulong length = 0;
            if (count > sizes.Length - sampleIndex) throw Invalid();
            for (var j = 0; j < count; j++) length += sizes[sampleIndex++];
            if (offset < previousEnd || !media.Any(x => offset >= (ulong)x.Start && offset + length <= (ulong)x.End)) throw Invalid();
            previousEnd = offset + length;
        }
        if (sampleIndex != sizes.Length) throw Invalid();
        return checked((int)Math.Ceiling(measured * 1000d / timescale));
    }

    private static uint ValidateDescriptor(byte[] data, Box box)
    {
        Need(box, 8); var position = box.Start + 4;
        var es = Descriptor(data, ref position, box.End, 3);
        var p = es.Start; if (es.End - p < 3) throw Invalid();
        p += 2; var flags = data[p++];
        if ((flags & 0x40) != 0) throw Invalid(); // No external elementary-stream URLs.
        if ((flags & 0x80) != 0) p += 2;
        if ((flags & 0x40) != 0) { if (p >= es.End) throw Invalid(); p += 1 + data[p]; }
        if ((flags & 0x20) != 0) p += 2;
        var decoder = Descriptor(data, ref p, es.End, 4);
        if (decoder.End - decoder.Start < 15 || data[decoder.Start] != 0x40 || (data[decoder.Start + 1] >> 2) != 5) throw Invalid();
        p = decoder.Start + 13;
        var config = Descriptor(data, ref p, decoder.End, 5);
        if (config.End - config.Start < 2) throw Invalid();
        var bits = U16(data, config.Start);
        var audioObject = bits >> 11; var rate = (bits >> 7) & 15; var channels = (bits >> 3) & 15;
        if (audioObject != 2 || rate > 12 || channels is not (1 or 2) || (bits & 4) != 0) throw Invalid();
        uint[] rates = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350];
        return rates[rate];
    }
    private static Box Descriptor(byte[] data, ref int p, int end, int tag)
    {
        if (p >= end || data[p++] != tag) throw Invalid();
        var length = 0; var finished = false;
        for (var i = 0; i < 4; i++) { if (p >= end) throw Invalid(); var b = data[p++]; length = checked(length * 128 + (b & 127)); if ((b & 128) == 0) { finished = true; break; } }
        if (!finished || length > end - p) throw Invalid();
        var result = new Box("descriptor", p, p + length); p += length; return result;
    }
    private static int Count(byte[] data, Box box, int width)
    { var n = U32(data, box.Start + 4); if (n > 100_000 || box.End - box.Start != 8L + n * width) throw Invalid(); return (int)n; }
    private static List<Box> Children(byte[] data, Box box) => Boxes(data, box.Start, box.End);
    private static Box One(IEnumerable<Box> boxes, string type) { var values = boxes.Where(x => x.Type == type).ToArray(); if (values.Length != 1) throw Invalid(); return values[0]; }
    private static List<Box> Boxes(byte[] data, int start, int end)
    {
        var result = new List<Box>();
        while (start < end)
        {
            if (end - start < 8 || result.Count >= 10_000) throw Invalid();
            ulong size = U32(data, start); var header = 8;
            if (size == 1) { if (end - start < 16) throw Invalid(); size = U64(data, start + 8); header = 16; }
            if (size == 0) size = (ulong)(end - start);
            if (size < (ulong)header || size > (ulong)(end - start)) throw Invalid();
            result.Add(new Box(Encoding.ASCII.GetString(data, start + 4, 4), start + header, start + (int)size)); start += (int)size;
        }
        return result;
    }
    private static void Need(Box box, int length) { if (box.End - box.Start < length) throw Invalid(); }
    private static ushort U16(byte[] data, int p) => BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(p, 2));
    private static uint U32(byte[] data, int p) => BinaryPrimitives.ReadUInt32BigEndian(data.AsSpan(p, 4));
    private static ulong U64(byte[] data, int p) => BinaryPrimitives.ReadUInt64BigEndian(data.AsSpan(p, 8));
    private static InvalidDataException Invalid() => new("chat_voice_container_invalid");
}
