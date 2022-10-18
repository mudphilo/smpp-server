package io.github.mudphilo.smppserver;

import com.cloudhopper.smpp.channel.SmppChannelConstants;
import com.cloudhopper.smpp.channel.SmppSessionPduDecoder;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoder;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoderContext;
import com.cloudhopper.smpp.type.LoggingOptions;
import com.cloudhopper.smpp.type.SmppChannelException;
import io.github.mudphilo.jmx.Management;
import io.github.mudphilo.jmx.annotation.Impact;
import io.github.mudphilo.jmx.annotation.ManagedAttribute;
import io.github.mudphilo.jmx.annotation.ManagedOperation;
import org.apache.logging.log4j.LogManager;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>An SMPP Server that accepts client connections and process SMPP packets. Every time a connection is accepted,
 * a new {@link SmppSession} object is created to handle the packets from that connection. It is only destroyed when
 * the client disconnects.</p>
 *
 * <p>Starting the SMPP Server is as simple as instantiating this class and calling the {@link #start()} method:</p>
 *
 * <pre>
 * 	SmppServer server = new SmppServer(4444);
 * 	server.start();
 * 	...
 *
 *  // somewhere else
 *  server.stop();
 * </pre>
 *
 * <p>To process the SMPP packets you will need to provide an implementation of the {@link PacketProcessor} interface
 * using the constructor  or the setter {@link #setPacketProcessor(PacketProcessor)}.
 * If no {@link PacketProcessor} is specified, a default implementation that always returns 0 (ESME_ROK in the SMPP
 * specification) is used.</p>
 *
 * @author German Escobar
 */
public class SmppServer {

    static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(SmppServer.class.getSimpleName());

    /**
     * Possible values for the status of the server.
     *
     * @author German Escobar
     */
    public enum Status {

        /**
         * The server is stopped. This is the initial state by the way.
         */
        STOPPED,

        /**
         * The server is stopping.
         */
        STOPPING,

        /**
         * The server is starting.
         */
        STARTING,

        /**
         * The server has started.
         */
        STARTED;

    }

    /**
     * A unique name for the server. Used to register the JMX MBean.
     */
    private final String name;

    /**
     * The port in which we are going to listen the connections.
     */
    private final int port;

    /**
     * The status of the server
     */
    private Status status = Status.STOPPED;

    private final ServerBootstrap serverBootstrap;

    private Channel serverChannel;

    private PacketProcessor packetProcessor;

    private final Map<Channel, SmppSession> sessions = new ConcurrentHashMap<>();

    private final AtomicInteger createdSessions = new AtomicInteger();

    private final AtomicInteger destroyedSessions = new AtomicInteger();

    private final AtomicInteger sessionId = new AtomicInteger();

    private SmppSessionListener sessionListener;

    /**
     * Constructor. Creates an instance with the specified port and {@link PacketProcessor} implementation. A
     *
     * @param port            the server will accept connections in this port.
     * @param packetProcessor the {@link PacketProcessor} implementation that will process the SMPP messages.
     */
    public SmppServer(int port, PacketProcessor packetProcessor) {

        this.port = port;
        this.packetProcessor = packetProcessor;

        ChannelFactory channelFactory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool(), Runtime.getRuntime().availableProcessors() * 3);
        this.serverBootstrap = new ServerBootstrap(channelFactory);

        ChannelPipeline pipeline = serverBootstrap.getPipeline();
        pipeline.addLast(SmppChannelConstants.PIPELINE_SERVER_CONNECTOR_NAME, new ServerChannelHandler());


        this.name = "Server-" + new Random().nextInt(10000);
        registerJMXBean();
    }

    public void registerJMXBean() {
        try {
            Management.register(this, "io.github.mudphilo.smppserver:type=" + name);

        } catch (Exception e) {
            logger.warn("Couldn't register SMPP Server as JMX Bean: {}", e.getMessage(), e);
        }
    }

    /**
     * Starts listening to client connections through the specified port.
     *
     * @throws IOException if an I/O error occurs when opening the socket.
     */
    @ManagedOperation(impact = Impact.ACTION)
    public void start() throws SmppChannelException {

        if (this.status != Status.STOPPED) {
            logger.warn("can't start SMPP Server, current status is {}", this.status);
            return;
        }

        logger.debug("starting the SMPP Server ... ");
        this.status = Status.STARTING;

        try {
            this.serverChannel = this.serverBootstrap.bind(new InetSocketAddress(port));
            logger.info("SMPP Server started on SMPP port [{}]", port);
        } catch (ChannelException e) {
            throw new SmppChannelException(e.getMessage(), e);
        }


        logger.info("<< SMPP Server running on port {} >>", port);
        this.status = Status.STARTED;
    }

    /**
     * Stops the server gracefully.
     */
    @ManagedOperation(impact = Impact.ACTION)
    public void stop() throws InterruptedException {

        if (this.status != Status.STARTED) {
            logger.warn("can't stop SMPP Server, current status is {} ", this.status);
            return;
        }

        // this will signal the ConnectionThread to stop accepting connections
        logger.debug("stopping the SMPP Server ... ");
        this.status = Status.STOPPING;

        for (Channel channel : sessions.keySet()) {

            try {
                channel.disconnect().await(500);
            } catch (InterruptedException e) {

                e.printStackTrace();
                throw e;
            }
        }

        // clean up all external resources
        if (this.serverChannel != null) {
            this.serverChannel.close().awaitUninterruptibly();
            this.serverChannel = null;
        }

        // the server has stopped
        status = Status.STOPPED;
        logger.info("<< SMPP Server stopped >>");

    }

    /**
     * Returns the opened sessions.
     *
     * @return a collection of Session objects.
     */
    public Collection<SmppSession> getSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * @return the status of the server.
     */
    public Status getStatus() {
        return status;
    }

    @ManagedAttribute
    public String getStatusString() {
        return status.name();
    }

    @ManagedAttribute
    public int getActiveSessions() {
        return sessions.size();
    }

    @ManagedAttribute
    public int getCreatedSessions() {
        return createdSessions.get();
    }

    @ManagedAttribute
    public int getDestroyedSessions() {
        return destroyedSessions.get();
    }

    /**
     * Sets the packet processor that will be used for new sessions. Old sessions will not be affected.
     *
     * @param packetProcessor the {@link PacketProcessor} implementation to be used.
     */
    public void setPacketProcessor(PacketProcessor packetProcessor) {

        if (packetProcessor == null) {
            throw new IllegalArgumentException("No packetProcessor specified");
        }

        this.packetProcessor = packetProcessor;
    }

    public void setSessionListener(SmppSessionListener sessionListener) {
        this.sessionListener = sessionListener;
    }

    /**
     * This is the NIO server channel handler that manages connections and disconnections of clients.
     *
     * @author German Escobar
     */
    private class ServerChannelHandler extends SimpleChannelUpstreamHandler {


        public ServerChannelHandler() {

        }

        @Override
        public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

            Channel channel = e.getChannel();

            SmppSession session = null;
            int id = sessionId.incrementAndGet();

            try {

                session = new SmppSession(id, channel, packetProcessor);
                logger.info("created session with id {} ", id);

            } catch (Exception f) {

                logger.warn("Couldn't create session with id {} as a JMX MBean: {} exp {}", id, f.getMessage(), f);

            }


            channel.getPipeline().addLast(SmppChannelConstants.PIPELINE_SESSION_PDU_DECODER_NAME,
                    new SmppSessionPduDecoder(new DefaultPduTranscoder(new DefaultPduTranscoderContext())));
            channel.getPipeline().addLast(SmppChannelConstants.PIPELINE_SESSION_WRAPPER_NAME, session);

            //assert session != null;
            //session.setPacketProcessor(packetProcessor);

            logger.info("session.isBound {}",session.isBound());
            logger.info("session.getStatusString() {}",session.getStatusString());

            sessions.put(channel, session);
            createdSessions.incrementAndGet();


            try {

                Management.register(session, "io.github.mudphilo.smppserver:type=Sessions,id=" + session.getId());

            } catch (Exception f) {

                logger.warn("Couldn't register session with id {} as a JMX MBean: {} {}", id, f.getMessage(), f);
            }

            if (sessionListener != null) {

                sessionListener.created(session);
            }

        }

        @Override
        public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

            SmppSession session = sessions.remove(e.getChannel());

            if (session != null) {

                logger.info("[session-id={}] disconnected", session.getId());

                destroyedSessions.incrementAndGet();

                try {

                    Management.unregister("io.github.mudphilo.smppserver:type=Sessions,id=" + session.getId());

                } catch (Exception f) {

                    logger.warn("Exception unregistering session {} : {}", session.getId(), f.getMessage(), f);
                }

                if (sessionListener != null) {

                    sessionListener.destroyed(session);
                }
            }

        }

    }

}
