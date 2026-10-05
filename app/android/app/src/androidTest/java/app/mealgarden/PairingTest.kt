package app.mealgarden

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingTest {
    @Test fun laptopGeneratedQrDecodesAndValidates() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = BitmapFactory.decodeStream(context.assets.open("pairing-qr.png"))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        val details = parsePairingQr(decoded.text)
        assertEquals("http://100.95.10.2:4783", details.endpoint)
        assertEquals("12345678", details.code)
    }
    @Test fun unrelatedQrAndUnsafeEndpointsAreRejected() {
        val invalid = listOf("https://example.com", "{}", "x".repeat(2049),
            """{"app":"meal-garden","version":2,"endpoint":"http://laptop:4783","code":"12345678"}""",
            """{"app":"meal-garden","version":1,"endpoint":"file:///etc/passwd","code":"12345678"}""",
            """{"app":"meal-garden","version":1,"endpoint":"http://user:password@laptop:4783","code":"12345678"}""",
            """{"app":"meal-garden","version":1,"endpoint":"http://laptop:4783/redirect?target=bad","code":"12345678"}""",
            """{"app":"meal-garden","version":1,"endpoint":"http://laptop:70000","code":"12345678"}""",
            """{"app":"meal-garden","version":1,"endpoint":"http://laptop:4783","code":"bad"}""")
        for (raw in invalid) {
            try { parsePairingQr(raw); fail("Accepted an invalid pairing QR") } catch (_: IllegalArgumentException) {}
        }
    }
}
