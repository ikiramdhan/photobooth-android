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
    fun info(id: Int): String {
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
}
