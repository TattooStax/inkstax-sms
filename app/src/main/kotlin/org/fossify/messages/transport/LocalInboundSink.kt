package org.fossify.messages.transport

import inkstax.sms.transport.InboundOutcome
import inkstax.sms.transport.InboundSink
import inkstax.sms.transport.InboundSms
import inkstax.sms.transport.inboundDispositionFor

/**
 * The local binding of the inbound side of the bridge seam (SMS-2).
 *
 * Local mode is in-process and synchronous: the fork's receivers have
 * already persisted the message through the telephony provider and the
 * app's own store before this sink is called, so handling here is the
 * shared disposition classification only. This binding deliberately
 * creates no second intake, store, or scheduler, talks to no network
 * endpoint, and holds no credential material — a later host or cloud
 * binding replaces this class behind the same [InboundSink] interface
 * without changing the receivers.
 */
class LocalInboundSink : InboundSink {

    override fun onInbound(message: InboundSms): InboundOutcome {
        return inboundDispositionFor(message)
    }
}
