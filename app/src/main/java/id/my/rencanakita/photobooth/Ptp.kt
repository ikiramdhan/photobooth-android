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
) {
    class Reply(val code: Int, val data: ByteArray)

    private var tid = 0
    private var pending = ByteArray(0)

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun fill(n: Int) {
        while (pending.size < n) {
            val tmp = ByteArray(65536)
            val r = conn.bulkTransfer(inEp, tmp, tmp.size, 5000)
            if (r < 0) throw IOException("USB tidak merespons (timeout)")
            if (r > 0) pending += tmp.copyOf(r)
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
        pending = pending.copyOfRange(len, pending.size)
        return Triple(type, code, payload)
    }

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
        val w = conn.bulkTransfer(outEp, out, out.size, 5000)
        if (w < 0) throw IOException("Gagal mengirim perintah ke kamera")
        var data = ByteArray(0)
        while (true) {
            val (type, c, payload) = nextContainer()
            when (type) {
                2 -> data += payload
                3 -> return Reply(c, data)
                else -> {}
            }
        }
    }

    fun openSession(): Int {
        tid = 0
        val r = command(0x1002, 1)
        tid = 1
        return r.code
    }

    fun closeSession(): Int {
        val r = command(0x1003)
        tid++
        return r.code
    }

    class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serial: String,
        val operations: List<Int>,
    )

    fun deviceInfo(): DeviceInfo {
        tid = 0
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
