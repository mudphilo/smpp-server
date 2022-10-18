package io.github.mudphilo.smppserver.packet;

public class Unbind extends SmppRequest {

	public Unbind() {
		super(SmppPacket.UNBIND);
	}

}
