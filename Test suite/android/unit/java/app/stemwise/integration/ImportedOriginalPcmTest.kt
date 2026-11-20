package app.stemwise.integration

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedOriginalPcmTest {
    @Test fun realDecodedStereoSamplesMustBeFiniteAndComplete() {
        fun samples(left: Float, right: Float) = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN).putFloat(left).putFloat(right).array()
        requireFiniteImportedOriginalPcm(samples(-1f, 1f), 0, 8)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalStateException::class.java) {
                requireFiniteImportedOriginalPcm(samples(0f, invalid), 0, 8)
            }
        }
        listOf(0, 4, 7).forEach { length ->
            assertThrows(IllegalStateException::class.java) {
                requireFiniteImportedOriginalPcm(ByteArray(8), 0, length)
            }
        }
    }
}
