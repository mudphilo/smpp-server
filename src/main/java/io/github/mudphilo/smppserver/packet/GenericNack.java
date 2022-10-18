package io.github.mudphilo.smppserver.packet;


/**
 * 
 * @author German Escobar
 */
public class GenericNack extends SmppResponse {

	public GenericNack() {
		super(SmppPacket.GENERIC_NACK);
	}
	
}
