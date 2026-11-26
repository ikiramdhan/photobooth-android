package id.my.rencanakita.photobooth

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Mendeteksi kamera USB (khususnya Canon) dan membaca info dasarnya lewat PTP. */
class UsbCamera(private val ctx: Context, private val onChange: () -> Unit) {
    companion object {
        const val CANON_VID = 0x04A9
        private const val ACTION_PERMISSION = "id.my.rencanakita.photobooth.USB_PERMISSION"
    }

    private val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) Thread { closeSess() }.start()
            onChange()
        }
    }

    fun start() {
        if (registered) return
        val f = IntentFilter()
        f.addAction(ACTION_PERMISSION)
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, f)
        }
        registered = true
    }

    fun stop() {
        Thread { closeSess() }.start()
        if (!registered) return
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
    }

    private fun isStillImage(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return true
        }
        return false
    }

    private fun find(id: Int): UsbDevice? = mgr.deviceList.values.firstOrNull { it.deviceId == id }

    private fun nameOf(d: UsbDevice): String {
        val n = try { d.productName } catch (_: Exception) { null }
        return n ?: String.format("USB %04x:%04x", d.vendorId, d.productId)
    }

    fun listJson(): String {
        val arr = JSONArray()
        mgr.deviceList.values.forEach { d ->
            val canon = d.vendorId == CANON_VID
            val ptp = isStillImage(d)
            if (!canon && !ptp) return@forEach
            arr.put(
                JSONObject()
                    .put("id", d.deviceId)
                    .put("name", nameOf(d))
                    .put("vid", String.format("%04x", d.vendorId))
                    .put("pid", String.format("%04x", d.productId))
                    .put("canon", canon)
                    .put("ptp", ptp)
                    .put("permitted", mgr.hasPermission(d)),
            )
        }
        return arr.toString()
    }

    fun requestPermission(id: Int) {
        val d = find(id) ?: return
        val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        mgr.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, intent, flags))
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    /** Membuka koneksi PTP, membaca info kamera, lalu menutup lagi. Panggil dari thread latar. */
    fun info(id: Int): String = synchronized(lock) { closeSessLocked(); infoLocked(id) }

    private fun infoLocked(id: Int): String {
        val d = find(id) ?: return err("Perangkat tidak ditemukan. Colok ulang kabelnya.")
        if (!mgr.hasPermission(d)) return err("Izin USB belum diberikan. Tekan Izinkan dulu.")
        val conn = mgr.openDevice(d) ?: return err("Tidak bisa membuka perangkat USB.")
        var claimed: android.hardware.usb.UsbInterface? = null
        try {
            var intf: android.hardware.usb.UsbInterface? = null
            for (i in 0 until d.interfaceCount) {
                val x = d.getInterface(i)
                if (x.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) { intf = x; break }
            }
            if (intf == null) return err("Tidak ada antarmuka foto (PTP) pada perangkat ini.")
            if (!conn.claimInterface(intf, true)) return err("Gagal mengambil antarmuka USB.")
            claimed = intf
            var inEp: android.hardware.usb.UsbEndpoint? = null
            var outEp: android.hardware.usb.UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            if (inEp == null || outEp == null) return err("Endpoint USB tidak lengkap.")
            val ptp = Ptp(conn, inEp, outEp)
            val di = ptp.deviceInfo()
            val sess = ptp.openSession()
            val sessionOk = sess == 0x2001 || sess == 0x201E
            if (sessionOk) try { ptp.closeSession() } catch (_: Exception) {}
            val ops = JSONArray()
            di.operations.forEach { ops.put(String.format("%04x", it)) }
            return JSONObject()
                .put("ok", true)
                .put("manufacturer", di.manufacturer)
                .put("model", di.model)
                .put("version", di.deviceVersion)
                .put("serial", di.serial)
                .put("sessionCode", String.format("%04x", sess))
                .put("sessionOk", sessionOk)
                .put("initiateCapture", di.operations.contains(0x100E))
                .put("canonRemote", di.operations.contains(0x9114))
                .put("operations", ops)
                .toString()
        } catch (e: Exception) {
            return err(e.message ?: e.javaClass.simpleName)
        } finally {
            try { if (claimed != null) conn.releaseInterface(claimed) } catch (_: Exception) {}
            conn.close()
        }
    }

    // ================= Rana Canon dan ambil foto =================
    private val lock = Any()
    private class Sess(val id: Int, val conn: android.hardware.usb.UsbDeviceConnection, val intf: android.hardware.usb.UsbInterface, val ptp: Ptp)
    private var sess: Sess? = null

    private fun closeSess() = synchronized(lock) { closeSessLocked() }

    private fun closeSessLocked() {
        val s = sess ?: return
        sess = null
        try { s.ptp.command(0x9114, 0) } catch (_: Exception) {}
        try { s.ptp.closeSession() } catch (_: Exception) {}
        try { s.conn.releaseInterface(s.intf) } catch (_: Exception) {}
        try { s.conn.close() } catch (_: Exception) {}
    }

    /** Kamera Canon pertama yang terhubung (atau null). */
    fun firstCanon(): UsbDevice? = mgr.deviceList.values.firstOrNull { it.vendorId == CANON_VID && isStillImage(it) }

    private fun openSess(d: UsbDevice): Sess {
        val conn = mgr.openDevice(d) ?: throw java.io.IOException("Tidak bisa membuka perangkat USB.")
        try {
            var intf: android.hardware.usb.UsbInterface? = null
            for (i in 0 until d.interfaceCount) {
                val x = d.getInterface(i)
                if (x.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) { intf = x; break }
            }
            if (intf == null) throw java.io.IOException("Tidak ada antarmuka foto (PTP) pada kamera.")
            if (!conn.claimInterface(intf, true)) throw java.io.IOException("Gagal mengambil antarmuka USB.")
            var inEp: android.hardware.usb.UsbEndpoint? = null
            var outEp: android.hardware.usb.UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            if (inEp == null || outEp == null) throw java.io.IOException("Endpoint USB tidak lengkap.")
            val ptp = Ptp(conn, inEp, outEp)
            val code = ptp.openSession()
            if (code != 0x2001 && code != 0x201E) throw java.io.IOException("Sesi kamera ditolak (0x" + code.toString(16) + ")")
            // Mode remote Canon: kamera menerima perintah rana dan mengirim event file baru.
            val rm = ptp.command(0x9114, 1)
            if (rm.code != 0x2001) throw java.io.IOException("Kamera menolak mode remote (0x" + rm.code.toString(16) + "). Pastikan kamera menyala dan bukan di mode video.")
            try { ptp.command(0x9115, 1) } catch (_: Exception) {}
            try { ptp.canonAdded() } catch (_: Exception) {}
            return Sess(d.deviceId, conn, intf, ptp)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Jepret dengan kamera Canon, tunggu file JPEG baru, unduh, dan simpan ke dir/last.jpg.
     * Panggil dari thread latar. Mengembalikan JSON {ok, bytes, name} atau {ok:false, error}.
     */
    fun capture(dir: java.io.File): String = synchronized(lock) {
        try {
            val d = firstCanon() ?: return@synchronized err("Kamera Canon tidak terdeteksi. Cek kabel OTG.")
            if (!mgr.hasPermission(d)) return@synchronized err("Izin USB belum diberikan.")
            var s = sess
            if (s == null || s.id != d.deviceId) { closeSessLocked(); s = openSess(d); sess = s }
            val ptp = s.ptp
            try { ptp.canonAdded() } catch (_: Exception) {} // buang event lama
            var r = ptp.command(0x9128, 3, 0)
            if (r.code != 0x2001) {
                try { ptp.command(0x9129, 3) } catch (_: Exception) {}
                Thread.sleep(500)
                r = ptp.command(0x9128, 3, 0)
            }
            try { ptp.command(0x9129, 3) } catch (_: Exception) {}
            if (r.code != 0x2001) return@synchronized err("Kamera tidak bisa menjepret (kode 0x" + r.code.toString(16) + "). Fokus gagal atau kamera sibuk.")
            val deadline = System.currentTimeMillis() + 15000
            var got: Ptp.Added? = null
            var sawOther = false
            while (got == null && System.currentTimeMillis() < deadline) {
                for (a in ptp.canonAdded()) {
                    if (a.format == 0x3801 || a.name.uppercase().endsWith(".JPG")) { got = a; break } else sawOther = true
                }
                if (got == null) Thread.sleep(150)
            }
            if (got == null) {
                return@synchronized err(
                    if (sawOther) "Kamera hanya menyimpan RAW. Ubah kualitas gambar ke JPEG atau RAW+JPEG."
                    else "Foto dari kamera tidak datang. Pastikan kartu memori terpasang dan kualitas gambar JPEG.",
                )
            }
            val obj = ptp.command(0x1009, got.oid)
            if (obj.code != 0x2001 || obj.data.isEmpty()) return@synchronized err("Gagal mengunduh foto dari kamera (0x" + obj.code.toString(16) + ").")
            try { ptp.command(0x9117, got.oid) } catch (_: Exception) {}
            dir.mkdirs()
            java.io.File(dir, "last.jpg").writeBytes(obj.data)
            JSONObject().put("ok", true).put("bytes", obj.data.size).put("name", got.name).toString()
        } catch (e: Exception) {
            closeSessLocked()
            err(e.message ?: e.javaClass.simpleName)
        }
    }
}
