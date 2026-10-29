package inkstax.sms.transport

/** Channel an outbound message is routed to. */
enum class OutboundChannel {
    SMS,
    MMS
}

/**
 * The SMS-vs-MMS routing decision, extracted unchanged from the
 * app's send entry point (SMS-1): a message goes over MMS when it
 * carries attachments, when it is long enough to be converted to
 * MMS, or when it addresses a group that is configured to be sent
 * as MMS. Everything else is SMS text and goes through
 * [MessageTransport].
 */
fun outboundChannel(
    hasAttachments: Boolean,
    isLongMms: Boolean,
    addressCount: Int,
    sendGroupAsMms: Boolean
): OutboundChannel {
    val isMms = hasAttachments || isLongMms || (addressCount > 1 && sendGroupAsMms)
    return if (isMms) OutboundChannel.MMS else OutboundChannel.SMS
}
