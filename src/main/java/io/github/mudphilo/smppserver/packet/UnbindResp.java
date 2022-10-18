package io.github.mudphilo.smppserver.packet;

/**
 * 
 * @author German Escobar
 */
public class UnbindResp extends SmppResponse {

	public UnbindResp() {
		super(SmppPacket.UNBIND_RESP);
	}

}
