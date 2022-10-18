package io.github.mudphilo.smppserver.packet;


/**
 * 
 * @author German Escobar
 */
public class EnquireLink extends SmppRequest {

	public EnquireLink() {
		super(SmppPacket.ENQUIRE_LINK);
	}
}
