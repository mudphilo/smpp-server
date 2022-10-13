package net.gescobar.smppserver;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import net.gescobar.jmx.annotation.ManagedAttribute;
import net.gescobar.jmx.annotation.ManagedOperation;
import net.gescobar.smppserver.packet.SmppPacket;
import net.gescobar.smppserver.packet.SmppRequest;
import net.gescobar.smppserver.packet.SmppResponse;
import net.gescobar.smppserver.packet.Unbind;
import net.gescobar.smppserver.packet.ch.PacketMapper;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudhopper.commons.util.windowing.Window;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoder;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoderContext;
import com.cloudhopper.smpp.transcoder.PduTranscoder;
import com.cloudhopper.smpp.type.SmppChannelException;

/**
 * <p>Represents an SMPP session with an SMPP client. When it receives an SMPP packet, it calls the 
 * {@link PacketProcessor#processPacket(SmppRequest, ResponseSender)} and responds with the returned value.</p>
 * 
 * <p><strong>Note:</strong> This object is created when a connection is accepted and destroyed when the client 
 * disconnects. Notice that this could happen before/after the bind and unbind packets. A session that is created
 * but still not bound is known to be in an idle state ({@link Status#IDLE}). A session that is was unbound (ie. 
 * using a unbind packet) is known to be in a dead state ({@link Status#DEAD}) and should reject any further
 * packet. </p>
 * 
 * @author German Escobar
 */
public class SmppSession extends SimpleChannelHandler {
	
	private Logger log = LoggerFactory.getLogger(SmppSession.class);

	/**
	 * Possible values for the status of the session.
	 * 
	 * @author German Escobar
	 */
	public enum Status {

		/**
		 * The connection is open but the client hasn't tried to bind or has tried but unsuccessfully.
		 */
		OPEN,

		/**
		 * The connection is open and the client is bound.
		 */
		BOUND,
		
		/**
		 * The connection is closed.
		 */
		CLOSED;
	}

	/**
	 * Possible values for the bind type of the session.
	 * 
	 * @author German Escobar
	 */
	public enum BindType {

		TRANSMITTER,

		RECEIVER,

		TRANSCIEVER;

	}
	
	public int sessionId;

	/**
	 * The status of the session.
	 */
	private Status status = Status.OPEN;

	/**
	 * The bind type of the session. Null if not bound.
	 */
	private BindType bindType;

	/**
	 * The systemId that was used to bind.
	 */
	private String systemId;
	
	/**
	 * The time in which the session was created.
	 */
	private Date creationTime;
	
	/**
	 * The channel from which we'll listen an to which we'll write
	 */
	private Channel channel;
	
	/**
	 * The class that will process the SMPP messages.
	 */
	private PacketProcessor packetProcessor;
	
	private PduTranscoder transcoder;
	
	/**
	 * Used to set the sequence number to packets sent to clients
	 */
	private AtomicInteger sequenceId = new AtomicInteger(0);
	
	/**
	 * Reusing the cloudhopper window mechanism to handle the response of packets sent through the 
	 * {@link #sendRequest(SmppRequest)} method.
	 */
	@SuppressWarnings("rawtypes")
	private final Window<Integer,PduRequest,PduResponse> sendWindow = 
			new Window<Integer,PduRequest,PduResponse>(10);
	
	/**
	 * Constructor.
	 * 
	 * @param sessionId
	 * @param channel
	 * @param packetProcessor
	 */
	public SmppSession(int sessionId, Channel channel, PacketProcessor packetProcessor) {
		
		if (channel == null) {
			throw new IllegalArgumentException("no channel specified");
		}
		
		if (packetProcessor == null) {
			throw new IllegalArgumentException("no packetProcessor specified");
		}
		
		this.sessionId = sessionId;
		this.channel = channel;
		this.packetProcessor = packetProcessor;
		this.transcoder = new DefaultPduTranscoder(new DefaultPduTranscoderContext());
		this.creationTime = new Date();
	}
	
	@ManagedAttribute
	public String getId() {
		return "session-" + sessionId;
	}

	/**
	 * This is called when a message is received through the channel link. it handles request and response PDU's
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		
		Pdu pdu = (Pdu) e.getMessage();
		
		// handle responses to packets that were sent using the sendRequest(...) method
		if (pdu.isResponse()) {
			
			PduResponse pduResponse = (PduResponse) pdu;
			this.sendWindow.complete(pduResponse.getSequenceNumber(), pduResponse);
			
			return;
		}
		
		// if packet is a bind request and session is already bound, respond with error
		if (BaseBind.class.isInstance(pdu) && isBound()) {
			
			log.warn(getLogHead() + "session with system id " + systemId + " is already bound");
			
			PduResponse response = createResponse((PduRequest) pdu, Response.ALREADY_BOUND);
			send(response);
			
			return;
		}
		
		// if not a bind packet and session is not bound, respond with error
		if (!BaseBind.class.isInstance(pdu) && !isBound()) {
			
			PduResponse response = createResponse((PduRequest) pdu, Response.INVALID_BIND_STATUS);
			send(response);
			
			return;
		}
		
		log.debug(getLogHead() + "received request PDU: " + pdu);
		
		ResponseSender responseSender = new OnlyOnceResponse( (PduRequest) pdu );

   	 	try {
   	 		packetProcessor.processPacket(sessionId, (SmppRequest) PacketMapper.map((PduRequest) pdu), responseSender );
   	 	} catch (Exception f) {
   	 		log.error("Exception calling the packet processor: " + f.getMessage(), f);
   	 	}
   	 	
	}
	
	/**
	 * Helper method. Creates a response PDU from the request and sets the command status from the {@link Response} 
	 * object. 
	 * 
	 * @param request
	 * @param response
	 * 
	 * @return the created PduResponse object
	 */
	private PduResponse createResponse(PduRequest<PduResponse> request, Response response) {
		
		PduResponse pduResponse = request.createResponse();
		pduResponse.setCommandStatus( response.getCommandStatus() );
		return pduResponse;
		
	}
	
	/**
	 * Helper method. Sends a PDU through the channel link
	 * 
	 * @param pdu the Pdu to be sent.
	 */
	private void send(Pdu pdu) {
		
		try {
			
			// encode the pdu into a buffer
	        ChannelBuffer buffer = transcoder.encode(pdu);
	
	        // always log the PDU
	        log.info(getLogHead() + "sending PDU to client: {}", pdu);
	
	        // write the pdu out & wait till its written
	        ChannelFuture channelFuture = this.channel.write(buffer).await();
	
	        // check if the write was a success
	        if (!channelFuture.isSuccess()) {
	        	throw new SmppChannelException(channelFuture.getCause().getMessage(), channelFuture.getCause());
	        }
	        
		} catch (Exception e) {
			log.error(getLogHead() + "fatal exception thrown while attempting to send PDU to client: {}", e);
		}
	}
	
	/**
	 * Unbinds (if the connection is bound) and closes the connection.
	 */
	@ManagedOperation
	public void close() {
		
		if (!isBound()) {
			disconnect();
		} else {
			sendRequest( new Unbind(), 500);
		}
	}
	
	/**
	 * Sends an {@link SmppRequest} to the client.
	 * 
	 * @param packet the request packet to send to the client.
	 * 
	 * @return the received {@link SmppResponse}
	 * @throws SmppException
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public SmppResponse sendRequest(SmppRequest packet, long timeout) throws SmppException {
		
		if (packet == null) {
			throw new IllegalArgumentException("No packet specified");
		}
		
		// send requests only if already bound
		if (!isBound()) {
			throw new IllegalStateException("The session is not bound.");
		}
		
		// only some packets can be sent to the client
		if (packet.getCommandId() != SmppPacket.DELIVER_SM &&
				packet.getCommandId() != SmppPacket.ENQUIRE_LINK &&
				packet.getCommandId() != SmppPacket.UNBIND) {
			throw new IllegalArgumentException("Not allowed to send this packet to the client. Possible packets: " +
					"deliver_sm, enquire_link, unbind");
		}
		
		// set the sequence number if not assigned
		if (packet.getSequenceNumber() == -1) {
			packet.setSequenceNumber( sequenceId.incrementAndGet() );
		}
		
		try {
			PduRequest pdu = (PduRequest) PacketMapper.map(packet);
	        
	        WindowFuture<Integer,PduRequest,PduResponse> future = null;
	        try {
	            future = sendWindow.offer(pdu.getSequenceNumber(), pdu, 30000, 60000, true);
	        } catch (Exception e) {
	        	throw new SmppException(e);
	        }
	        
	        send(pdu);
	        
	        // wait for the response to arrive
	        /* TODO we could return a future to the client or notify a callback object */
	        future.await(timeout);
	        
	        if (packet.getCommandId() == SmppPacket.UNBIND) {
	        	disconnect();
	        }
	        
	        return (SmppResponse) PacketMapper.map( future.getResponse() );
	        
		} catch (Exception e) {
			throw new SmppException(e);
		}
		
	}
	
	/**
	 * Sets the status to Status.CLOSED and loses the channel link.
	 * 
	 * @throws IOException if there is a problem closing the socket.
	 */
	private void disconnect() {
		
		this.status = Status.CLOSED;
		
		try {
			channel.disconnect().await(500);
		} catch (InterruptedException e) { }
		
	}

	/**
	 * @return the status of the session.
	 */
	public Status getStatus() {
		return status;
	}
	
	@ManagedAttribute
	public String getStatusString() {
		return status.name();
	}
	
	/**
	 * A utility method to easily check if the session is bound.
	 * 
	 * @return
	 */
	public boolean isBound() {
		return status == Status.BOUND;
	}

	/**
	 * Tells if the session was bound in transceiver, receiver or transmitter mode. 
	 * 
	 * @return the bind type of the session.
	 * @throws IllegalStateException if the session is not bound.
	 */
	public BindType getBindType() throws IllegalStateException {

		if (!status.equals(Status.BOUND)) {
			throw new IllegalStateException("The session is not bound.");
		}

		return bindType;
	}
	
	@ManagedAttribute
	public String getBindTypeString() {
		try {
			return getBindType().name();
		} catch (IllegalStateException e) {
			return "Session not bound";
		}
	}

	/**
	 * @return the system id which was used by the client to bind the session.
	 * @throws IllegalStateException if the session is not bound.
	 */
	@ManagedAttribute
	public String getSystemId() throws IllegalStateException {

		if (!status.equals(Status.BOUND)) {
			throw new IllegalStateException("The session is not bound.");
		}

		return systemId;
	}
	
	public Date creationTime() {
		return creationTime;
	}
	
	@ManagedAttribute
	public String getCreated() {
		
		long creation = creationTime.getTime();
		long actual = System.currentTimeMillis();
		
		long diffMillis = (actual - creation) / 1000;
		
		if (diffMillis < 60) {
			return "just now";
		}
		
		if (diffMillis < 3600) {
			int diff = new Double(Math.floor(diffMillis / 60)).intValue();
			return "about " + diff + " " + (diff == 1 ? "minute" : "minutes") + " ago";
		}
		
		if (diffMillis < 86400) {
			int diff = new Double(Math.floor(diffMillis / 3600)).intValue();
			return "about" + diff + " " + (diff == 1 ? "hour" : "hours") + " ago";
		}
		
		int diff = new Double(Math.floor(diffMillis / 86400)).intValue();
		return  diff + " " + (diff == 1 ? "day" : "days") + " ago";
		
	}
	
	/**
	 * Sets the packet processor that will be used to process the packets.
	 * 
	 * @param packetProcessor the {@link PacketProcessor} implementation to be used.
	 */
	public void setPacketProcessor(PacketProcessor packetProcessor) {
		this.packetProcessor = packetProcessor;
	}

	/**
	 * @return the {@link PacketProcessor} implementation that is being used in this session.
	 */
	public PacketProcessor getPacketProcessor() {
		return packetProcessor;
	}
	
	private String getLogHead() {
		return "[session-id=" + sessionId + (systemId != null ? ",system-id=" + systemId : "") + "] ";
	}
	
	/**
	 * This is the {@link ResponseSender} implementation that is passed to the 
	 * {@link PacketProcessor#processPacket(SMPPPacket, ResponseSender)} method. It checks that the response is sent 
	 * only once.
	 * 
	 * @author German Escobar
	 */
    private class OnlyOnceResponse implements ResponseSender {

		private PduRequest<PduResponse> pduRequest;
    	
    	private boolean responseSent = false;

		public OnlyOnceResponse(PduRequest<PduResponse> pduRequest) {
    		this.pduRequest = pduRequest;
    	}

		@SuppressWarnings("rawtypes")
		@Override
		public void send(Response response) {
			
			if (responseSent) {
				log.warn(getLogHead() + "response for this request was already sent to the client ... ignoring");
				return;
			}
			
			try {
				
				PduResponse pduResponse = createResponse(pduRequest, response);
				
				int commandId = pduRequest.getCommandId();
				int commandStatus = response.getCommandStatus();
				
				if (BaseBind.class.isInstance(pduRequest)) {
					
					if (commandStatus == Response.OK.getCommandStatus()) {
						
						status = Status.BOUND;

		   	 			if (commandId == SmppConstants.CMD_ID_BIND_RECEIVER) {
				   			bindType = BindType.RECEIVER;
				   		} else if (commandId == SmppConstants.CMD_ID_BIND_TRANSMITTER) {
				   			bindType = BindType.TRANSMITTER;
				   		} else if (commandId == SmppConstants.CMD_ID_BIND_TRANSCEIVER) {
				   			bindType = BindType.TRANSCIEVER;
				   		}

		   	 			BaseBind bind = (BaseBind) pduRequest;
		   	 			systemId = bind.getSystemId();
		   	 			
		   	 			// this is important to support tlv parameters
		   	 			pduResponse.addOptionalParameter( new Tlv(SmppConstants.TAG_SC_INTERFACE_VERSION, new byte[] { SmppConstants.VERSION_3_4 }) );
		   	 			
		   	 			log.info("[" + systemId + "] session created with bind type: " + bindType);
					}
					
				} else {
					
					if (commandId == SmppPacket.SUBMIT_SM) {
						
						if (response.getMessageId() != null) {
							SubmitSmResp submitResp = (SubmitSmResp) pduResponse;
							submitResp.setMessageId( response.getMessageId() );
	   	 				}
		
					}
					
				}
				
				SmppSession.this.send(pduResponse);
				
				// handle unbind request
				if (commandId == SmppPacket.UNBIND) {	
					disconnect();
				}
				
			} catch (Exception e) {
				log.error(getLogHead() + "Exception sending response: " + e.getMessage(), e);
			}
		}
    	
    }
	
}
