package app.stemwise.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class StemwiseSliderAuraPolicyTest {
    @Test
    fun keyUsesTwentyFiveTicksAcrossTheExactThumbTravel() {
        val fractions = stemwiseSliderTickFractions(steps = 23)

        assertEquals(25, fractions.size)
        assertEquals(0f, fractions.first(), 0f)
        assertEquals(.5f, fractions[12], 0f)
        assertEquals(1f, fractions.last(), 0f)
    }

    @Test
    fun continuousSliderDoesNotInventDiscreteTicks() {
        assertEquals(0, stemwiseSliderTickFractions(steps = 0).size)
    }

    @Test
    fun bipolarKeyTrackStartsAtCenterAndExtendsOnlyTowardTheShift() {
        assertEquals(0.5f..0.625f, stemwiseSliderActiveTrackRange(.625f, centered = true))
        assertEquals(0.25f..0.5f, stemwiseSliderActiveTrackRange(.25f, centered = true))
        assertEquals(0.5f..0.5f, stemwiseSliderActiveTrackRange(.5f, centered = true))
        assertEquals(0f..0.625f, stemwiseSliderActiveTrackRange(.625f, centered = false))
    }
}
