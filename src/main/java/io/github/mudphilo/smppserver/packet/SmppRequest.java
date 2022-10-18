package io.github.mudphilo.smppserver.packet;

/**
 * 
 * @author German Escobar
 */
public abstract class SmppRequest extends SmppPacket {

	protected SmppRequest(int commandId) {
		super(commandId);
	}
	
}
