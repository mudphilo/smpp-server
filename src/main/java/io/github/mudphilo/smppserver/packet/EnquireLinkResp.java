package io.github.mudphilo.smppserver.packet;

/**
 * 
 * @author German Escobar
 */
public class EnquireLinkResp extends SmppResponse {

	public EnquireLinkResp() {
		super(SmppPacket.ENQUIRE_LINK_RESP);
	}
}
