package io.gnosis.safe.authenticator.ui.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/*
 * Check https://github.com/walleth/walleth/tree/master/app/src/main/java/org/walleth/activities/qrscan
 */
class QRCodeDecoder constructor() {
    fun decode(data: ByteArray, width: Int, height: Int): String {
        val centerX = width / 2
        val centerY = height / 2

        var size = Math.min(width, height)
        size = (size.toDouble() * ReticleView.FRAME_SCALE).toInt()

        val halfSize = size / 2

        val left = centerX - halfSize
        val top = centerY - halfSize

        val source = PlanarYUVLuminanceSource(data, width, height, left, top, size, size, false)
        val image = BinaryBitmap(HybridBinarizer(source))
        val reader = QRCodeReader()

        return reader.decode(image).text
    }
}
