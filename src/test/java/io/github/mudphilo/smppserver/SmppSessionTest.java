package io.github.mudphilo.smppserver;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.mudphilo.smppserver.PacketProcessor;
import io.github.mudphilo.smppserver.Response;
import io.github.mudphilo.smppserver.ResponseSender;
import io.github.mudphilo.smppserver.SmppSession;
import io.github.mudphilo.smppserver.packet.Bind;
import io.github.mudphilo.smppserver.packet.SmppRequest;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.MessageEvent;
import org.testng.annotations.Test;

import com.cloudhopper.smpp.pdu.BindTransceiver;

import java.sql.SQLException;

public class SmppSessionTest {
	
	@Test(expectedExceptions=IllegalStateException.class)
	public void getBindTypeShouldFailIfNotBound() throws Exception {
		SmppSession session = new SmppSession(463, mock(Channel.class), new DefaultPacketProcessor() );
		session.getBindType();
	}
	
	@Test(expectedExceptions=IllegalStateException.class)
	public void getSystemIdShouldFailIfNotBound() throws Exception {
		SmppSession session = new SmppSession(294, mock(Channel.class), new DefaultPacketProcessor() );
		session.getSystemId();
	}
	
	@Test
	public void shouldCallCustomPacketProcessor() throws Exception {

		PacketProcessor packetProcessor = mock(PacketProcessor.class);
		SmppSession session = new SmppSession(495, mock(Channel.class), packetProcessor);
		
		MessageEvent event = mock(MessageEvent.class);
		when(event.getMessage()).thenReturn(new BindTransceiver());
		
		session.messageReceived(null, event);
		
		verify(packetProcessor).processPacket(any(Integer.class),any(SmppRequest.class), any(ResponseSender.class));
		
	}
	
	private class DefaultPacketProcessor implements PacketProcessor {

		/**
		 * Process an SMPP Packet and uses the {@link ResponseSender} object to send a response back to the client.
		 *
		 * @param sessionID      current session ID
		 * @param packet         the {@link SmppRequest} to be processed.
		 * @param responseSender used to send the response to the client.
		 */
		@Override
		public void processPacket(int sessionID, SmppRequest packet, ResponseSender responseSender) throws SQLException {

			responseSender.send(Response.OK);
		}
	}
	
}
