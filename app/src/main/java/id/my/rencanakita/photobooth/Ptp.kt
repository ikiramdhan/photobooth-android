package id.my.rencanakita.photobooth

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Protokol PTP (Picture Transfer Protocol) paling dasar lewat USB bulk. */
class Ptp(
    private val conn: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val intfNum: Int = 0,
) {
    class Reply(val code: Int, val data: ByteArray)

    private var tid = 0
    private var pending = ByteArray(1 shl 18)
    private var pLen = 0

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Pastikan minimal n byte sudah ada di buffer. Buffer membesar bertahap, tanpa menyalin ulang tiap paket. */
    private fun fill(n: Int) {
        while (pLen < n) {
            if (pending.size - pLen < 65536) pending = pending.copyOf(maxOf(pending.size * 2, pLen + 65536))
            val r = conn.bulkTransfer(inEp, pending, pLen, 65536, 8000)
            if (r < 0) throw IOException("USB tidak merespons (timeout)")
            pLen += r
        }
    }

    private fun nextContainer(): Triple<Int, Int, ByteArray> {
        fill(12)
        val len = u32(pending, 0)
        if (len < 12) throw IOException("Paket PTP tidak valid")
        fill(len)
        val type = u16(pending, 4)
        val code = u16(pending, 6)
        val payload = pending.copyOfRange(12, len)
        System.arraycopy(pending, len, pending, 0, pLen - len)
        pLen -= len
        return Triple(type, code, payload)
    }

    /** Pulihkan kamera yang macet: reset perangkat PTP dan buka blokir endpoint. Aman dipanggil kapan saja. */
    fun recover(full: Boolean = true) {
        pLen = 0
        if (full) try { conn.controlTransfer(0x21, 0x66, 0, intfNum, null, 0, 2000) } catch (_: Exception) {} // Device Reset
        for (ep in arrayOf(inEp, outEp)) {
            try { conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000) } catch (_: Exception) {} // CLEAR_FEATURE(ENDPOINT_HALT)
        }
        try { Thread.sleep(400) } catch (_: Exception) {}
        tid = 0
    }

    /** Buang sisa data lama di buffer (dipakai setelah error). */
    fun reset() { pLen = 0 }

    /** Kirim satu perintah, lalu baca data (kalau ada) sampai balasan akhir. */
    fun command(code: Int, vararg params: Int): Reply {
        val len = 12 + 4 * params.size
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(len)
        bb.putShort(1) // jenis: command
        bb.putShort(code.toShort())
        bb.putInt(tid)
        params.forEach { bb.putInt(it) }
        val out = bb.array()
        var w = conn.bulkTransfer(outEp, out, out.size, 3000)
        if (w < 0) { recover(true); w = conn.bulkTransfer(outEp, out, out.size, 3000) }
        if (w < 0) throw IOException("Gagal mengirim perintah ke kamera (0x" + code.toString(16) + "). Matikan lalu nyalakan kamera, colok ulang kabel")
        val data = java.io.ByteArrayOutputStream()
        while (true) {
            val (type, c, payload) = nextContainer()
            when (type) {
                2 -> data.write(payload)
                3 -> { tid++; return Reply(c, data.toByteArray()) }
                else -> {}
            }
        }
    }

    fun openSession(): Int {
        tid = 0
        return command(0x1002, 1).code
    }

    fun closeSession(): Int = command(0x1003).code

    // ---- Ekstensi Canon EOS ----
    class Added(val oid: Int, val format: Int, val name: String)

    /** Baca event Canon (0x9116) dan ambil objek baru yang tercatat. */
    fun canonAdded(): List<Added> {
        val r = command(0x9116)
        val d = r.data
        val out = ArrayList<Added>()
        var o = 0
        while (o + 8 <= d.size) {
            val sz = u32(d, o)
            val t = u32(d, o + 4)
            if (sz < 8 || o + sz > d.size) break
            if ((t == 0xC181 || t == 0xC1A7) && o + 40 < d.size) {
                val nameOff = o + if (t == 0xC181) 32 else 40
                var e = nameOff
                while (e < o + sz && d[e].toInt() != 0) e++
                out.add(Added(u32(d, o + 8), u16(d, o + 16), String(d, nameOff, maxOf(0, e - nameOff), Charsets.US_ASCII)))
            }
            o += sz
        }
        return out
    }

    class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serial: String,
        val operations: List<Int>,
    )

    fun deviceInfo(): DeviceInfo {
        val r = command(0x1001)
        if (r.code != 0x2001) throw IOException("GetDeviceInfo ditolak (kode 0x" + r.code.toString(16) + ")")
        val b = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
        b.position(2 + 4 + 2) // versi standar, ID ekstensi, versi ekstensi
        readStr(b) // deskripsi ekstensi
        b.position(b.position() + 2) // mode fungsional
        val ops = readArr(b)
        readArr(b) // event
        readArr(b) // properti perangkat
        readArr(b) // format tangkap
        readArr(b) // format gambar
        val man = readStr(b)
        val model = readStr(b)
        val ver = readStr(b)
        val ser = readStr(b)
        return DeviceInfo(man, model, ver, ser, ops)
    }

    private fun readStr(b: ByteBuffer): String {
        val n = b.get().toInt() and 0xFF
        if (n == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until n) {
            val ch = b.short.toInt() and 0xFFFF
            if (ch != 0) sb.append(ch.toChar())
        }
        return sb.toString()
    }

    private fun readArr(b: ByteBuffer): List<Int> {
        val n = b.int
        if (n < 0 || n > 10000) throw IOException("Data PTP tidak valid")
        val out = ArrayList<Int>(n)
        for (i in 0 until n) out.add(b.short.toInt() and 0xFFFF)
        return out
    }
}
