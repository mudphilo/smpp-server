package io.github.mudphilo.smppserver.packet;

/**
 * 
 * @author German Escobar
 */
public abstract class SmppResponse extends SmppPacket {

	protected SmppResponse(int commandId) {
		super(commandId);
	}
	
}
