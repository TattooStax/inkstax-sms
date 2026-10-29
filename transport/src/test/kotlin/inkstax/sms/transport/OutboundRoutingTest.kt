package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboundRoutingTest {

    @Test
    fun `plain single-address text is SMS`() {
        val channel = outboundChannel(
            hasAttachments = false,
            isLongMms = false,
            addressCount = 1,
            sendGroupAsMms = false
        )
        assertEquals(OutboundChannel.SMS, channel)
    }

    @Test
    fun `attachments route to MMS`() {
        val channel = outboundChannel(
            hasAttachments = true,
            isLongMms = false,
            addressCount = 1,
            sendGroupAsMms = false
        )
        assertEquals(OutboundChannel.MMS, channel)
    }

    @Test
    fun `long message converted to MMS routes to MMS`() {
        val channel = outboundChannel(
            hasAttachments = false,
            isLongMms = true,
            addressCount = 1,
            sendGroupAsMms = false
        )
        assertEquals(OutboundChannel.MMS, channel)
    }

    @Test
    fun `group message routes to MMS only when group-as-MMS is on`() {
        val asMms = outboundChannel(
            hasAttachments = false,
            isLongMms = false,
            addressCount = 2,
            sendGroupAsMms = true
        )
        assertEquals(OutboundChannel.MMS, asMms)

        val asSms = outboundChannel(
            hasAttachments = false,
            isLongMms = false,
            addressCount = 2,
            sendGroupAsMms = false
        )
        assertEquals(OutboundChannel.SMS, asSms)
    }
}
