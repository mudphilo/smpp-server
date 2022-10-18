package io.github.mudphilo.smppserver.packet;

/**
 * 
 * @author German Escobar
 */
public class DeliverSmResp extends SmppResponse {

	public DeliverSmResp() {
		super(SmppPacket.DELIVER_SM_RESP);
	}
	
}
