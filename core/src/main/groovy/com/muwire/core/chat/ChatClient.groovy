package com.muwire.core.chat

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.Executors

import com.muwire.core.Constants
import com.muwire.core.EventBus
import com.muwire.core.MuWireSettings
import com.muwire.core.Persona
import com.muwire.core.connection.Endpoint
import com.muwire.core.connection.I2PConnector
import com.muwire.core.trust.TrustService
import com.muwire.core.util.DataUtil

import groovy.util.logging.Log

@Log
class ChatClient implements Closeable {
    
    private static final long REJECTION_BACKOFF = 60 * 1000
    
    private static final Executor CONNECTOR = Executors.newCachedThreadPool()
    
    private final I2PConnector connector
    private final EventBus eventBus
    private final Persona host, me
    private final TrustService trustService
    private final MuWireSettings settings
    
    private volatile ChatConnection connection
    private volatile boolean connectInProgress
    private volatile long lastRejectionTime
    private volatile Thread connectThread
    
    ChatClient(I2PConnector connector, EventBus eventBus, Persona host, Persona me, TrustService trustService,
        MuWireSettings settings) {
        this.connector = connector
        this.eventBus = eventBus
        this.host = host
        this.me = me
        this.trustService = trustService
        this.settings = settings
    }
    
    void connectIfNeeded() {
        if (connection != null || connectInProgress || (System.currentTimeMillis() - lastRejectionTime < REJECTION_BACKOFF))
            return
        CONNECTOR.execute({connect()})
    }
    
    private void connect() {
        connectInProgress = true
        connectThread = Thread.currentThread()
        Endpoint endpoint = null
        try {
            eventBus.publish(new ChatConnectionEvent(status : ChatConnectionAttemptStatus.CONNECTING, persona : host))
            endpoint = connector.connect(host.destination)
            DataOutputStream dos = new DataOutputStream(endpoint.getOutputStream())
            DataInputStream dis = new DataInputStream(endpoint.getInputStream())
            
            dos.with {
                write("IRC\r\n".getBytes(StandardCharsets.US_ASCII))
                write("Version:${Constants.CHAT_VERSION}\r\n".getBytes(StandardCharsets.US_ASCII))
                write("Persona:${me.toBase64()}\r\n".getBytes(StandardCharsets.US_ASCII))
                write("\r\n".getBytes(StandardCharsets.US_ASCII))
                flush()
            }
                
            String codeString = DataUtil.readTillRN(dis)
            int code = Integer.parseInt(codeString.split(" ")[0])
            
            if (code == 429) {
                eventBus.publish(new ChatConnectionEvent(status : ChatConnectionAttemptStatus.REJECTED, persona : host))
                endpoint.close()
                lastRejectionTime = System.currentTimeMillis()
                return
            }
            
            if (code != 200) 
                throw new Exception("unknown code $code")

            Map<String,String> headers = DataUtil.readAllHeaders(dis)
            if (!headers.containsKey('Version'))
                throw new Exception("Version header missing")
            
            int version = Integer.parseInt(headers['Version'])
            if (version != Constants.CHAT_VERSION)
                throw new Exception("Unknown chat version $version")
            
            connection = new ChatConnection(eventBus, endpoint, host, false, trustService, settings)
            connection.start()
            eventBus.publish(new ChatConnectionEvent(status : ChatConnectionAttemptStatus.SUCCESSFUL, persona : host, 
                connection : connection))
        } catch (Exception e) {
            eventBus.publish(new ChatConnectionEvent(status : ChatConnectionAttemptStatus.FAILED, persona : host))
            endpoint?.close()
        } finally {
            connectInProgress = false
            connectThread = null
        }
    }
    
    void disconnected() {
        connectInProgress = false
        connection = null
    }
    
    @Override
    public void close() {
        connectThread?.interrupt()
        connection?.close()
        eventBus.publish(new ChatConnectionEvent(status : ChatConnectionAttemptStatus.DISCONNECTED, persona : host))
    }
    
    void ping() {
        connection?.sendPing()
    }
}
