package com.example

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalPacketRouter(
    private val vpnInterface: ParcelFileDescriptor,
    private val logCallback: (String) -> Unit
) {
    private val TAG = "LocalPacketRouter"
    private var isRunning = false
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    
    private val selector: Selector = Selector.open()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    // Pool of reusable Buffers
    private val packetHeaderBuffer = ByteBuffer.allocate(65535)

    class TcpSession(
        val clientIp: String,
        val clientPort: Int,
        val destIp: String,
        val destPort: Int,
        var socketChannel: SocketChannel? = null,
        var mySequenceNum: Long = 1000L,
        var myAckNum: Long = 0L,
        var isHandshakeDone: Boolean = false,
        var isClosing: Boolean = false
    ) {
        val key = "$clientIp:$clientPort->$destIp:$destPort"
    }

    class UdpSession(
        val clientIp: String,
        val clientPort: Int,
        val destIp: String,
        val destPort: Int,
        val datagramChannel: DatagramChannel
    ) {
        val key = "$clientIp:$clientPort->$destIp:$destPort"
        var lastActiveTime = System.currentTimeMillis()
    }

    fun start() {
        isRunning = true
        log("Packet Translation Layer started.", showInUi = true)
        
        // 1. Thread to read from VPN TUN tun0 interface
        executor.submit { runTunReaderLoop() }
        
        // 2. Thread to read from native network Sockets (NIO Selector)
        executor.submit { runNetworkSelectorLoop() }
    }

    fun stop() {
        isRunning = false
        try {
            selector.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing selector", e)
        }
        
        for (session in tcpSessions.values) {
            try {
                session.socketChannel?.close()
            } catch (e: Exception) {}
        }
        tcpSessions.clear()

        for (session in udpSessions.values) {
            try {
                session.datagramChannel.close()
            } catch (e: Exception) {}
        }
        udpSessions.clear()

        executor.shutdownNow()
        log("Packet Translation Layer stopped.", showInUi = true)
    }

    private fun log(message: String, showInUi: Boolean = false) {
        Log.i(TAG, message)
        if (showInUi) {
            logCallback(message)
        }
    }

    private fun runTunReaderLoop() {
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val packetBuffer = ByteBuffer.allocate(65535)

        while (isRunning) {
            try {
                packetBuffer.clear()
                val bytesRead = inputStream.read(packetBuffer.array())
                if (bytesRead > 0) {
                    LocalVpnService.incrementBytesRouted(bytesRead.toLong())
                    packetBuffer.limit(bytesRead)
                    
                    // Create a copy for async handling so we don't block the TUN read
                    val pBuffer = ByteBuffer.allocate(bytesRead)
                    packetBuffer.rewind()
                    pBuffer.put(packetBuffer)
                    pBuffer.flip()
                    
                    try {
                        handleIncomingPacket(pBuffer)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling incoming packet", e)
                    }
                } else if (bytesRead < 0) {
                    break
                }
            } catch (e: IOException) {
                if (!isRunning) break
                Log.e(TAG, "IOException reading from TUN", e)
            }
        }
    }

    private fun handleIncomingPacket(buffer: ByteBuffer) {
        val packet = Packet(buffer)
        if (packet.version != 4) {
            // Drop invalid/IPv6/non-IPv4 packet early to ensure robust routing
            return
        }
        
        if (packet.protocol == Packet.PROTOCOL_TCP) {
            handleTcpPacket(packet)
        } else if (packet.protocol == Packet.PROTOCOL_UDP) {
            handleUdpPacket(packet)
        }
    }

    private fun handleTcpPacket(packet: Packet) {
        val srcIp = packet.sourceIpToString()
        val srcPort = packet.sourcePort
        val destIp = packet.destIpToString()
        val destPort = packet.destinationPort
        val sessionKey = "$srcIp:$srcPort->$destIp:$destPort"

        val flags = packet.tcpFlags
        val isSyn = (flags and Packet.TCP_FLAG_SYN) != 0
        val isAck = (flags and Packet.TCP_FLAG_ACK) != 0
        val isFin = (flags and Packet.TCP_FLAG_FIN) != 0
        val isRst = (flags and Packet.TCP_FLAG_RST) != 0

        var session = tcpSessions[sessionKey]

        if (isRst) {
            if (session != null) {
                closeTcpSession(session)
            }
            return
        }

        if (isSyn) {
            if (session != null) {
                closeTcpSession(session)
            }
            
            log("Connecting TCP: client $srcIp:$srcPort metadata bypass pointing to real: $destIp:$destPort")
            val newSession = TcpSession(srcIp, srcPort, destIp, destPort)
            newSession.myAckNum = packet.sequenceNumber + 1
            
            try {
                val socketChannel = SocketChannel.open()
                socketChannel.configureBlocking(false)
                socketChannel.connect(InetSocketAddress(InetAddress.getByName(destIp), destPort))
                newSession.socketChannel = socketChannel
                
                tcpSessions[sessionKey] = newSession
                
                // Register in selector to track when connection is ready or has data
                synchronized(selector) {
                    selector.wakeup()
                    socketChannel.register(selector, SelectionKey.OP_CONNECT, newSession)
                }
                
                // Send SYN-ACK back to client virtual handshaking immediately
                sendTcpControlPacket(newSession, Packet.TCP_FLAG_SYN or Packet.TCP_FLAG_ACK)
                // Increment sequence number since SYN counts as 1 byte
                newSession.mySequenceNum++
            } catch (e: Exception) {
                log("Failed to open connection to $destIp:$destPort: ${e.message}", showInUi = true)
                sendTcpEmptyAckOrRst(packet, Packet.TCP_FLAG_RST)
            }
            return
        }

        if (session == null) {
            // Received random packet without active session, respond with RST to reset
            sendTcpEmptyAckOrRst(packet, Packet.TCP_FLAG_RST)
            return
        }

        // Keep track of ACKs we received
        if (isAck) {
            session.mySequenceNum = packet.ackNumber
            if (!session.isHandshakeDone) {
                session.isHandshakeDone = true
            }
        }

        if (packet.payloadSize > 0) {
            session.myAckNum = packet.sequenceNumber + packet.payloadSize
            val channel = session.socketChannel
            if (channel != null && channel.isConnected) {
                try {
                    val payload = ByteBuffer.allocate(packet.payloadSize)
                    packet.buffer.position(packet.payloadOffset)
                    payload.put(packet.buffer)
                    payload.flip()
                    
                    while (payload.hasRemaining()) {
                        channel.write(payload)
                    }
                    
                    // Acknowledge the receipt of data to the client
                    sendTcpControlPacket(session, Packet.TCP_FLAG_ACK)
                } catch (e: IOException) {
                    log("TCP send error $sessionKey: ${e.message}")
                    sendTcpControlPacket(session, Packet.TCP_FLAG_RST)
                    closeTcpSession(session)
                }
            } else {
                // Buffer or queue the write until connected if in progress
            }
        }

        if (isFin) {
            session.myAckNum = packet.sequenceNumber + 1
            sendTcpControlPacket(session, Packet.TCP_FLAG_ACK or Packet.TCP_FLAG_FIN)
            closeTcpSession(session)
        }
    }

    private fun handleUdpPacket(packet: Packet) {
        val srcIp = packet.sourceIpToString()
        val srcPort = packet.sourcePort
        val destIp = packet.destIpToString()
        val destPort = packet.destinationPort
        val sessionKey = "$srcIp:$srcPort->$destIp:$destPort"

        var session = udpSessions[sessionKey]
        if (session == null) {
            try {
                val datagramChannel = DatagramChannel.open()
                datagramChannel.configureBlocking(false)
                datagramChannel.connect(InetSocketAddress(InetAddress.getByName(destIp), destPort))
                
                session = UdpSession(srcIp, srcPort, destIp, destPort, datagramChannel)
                udpSessions[sessionKey] = session

                synchronized(selector) {
                    selector.wakeup()
                    datagramChannel.register(selector, SelectionKey.OP_READ, session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create UDP socket to $destIp:$destPort", e)
                return
            }
        }

        session.lastActiveTime = System.currentTimeMillis()
        if (packet.payloadSize > 0) {
            try {
                val payload = ByteBuffer.allocate(packet.payloadSize)
                packet.buffer.position(packet.payloadOffset)
                payload.put(packet.buffer)
                payload.flip()
                session.datagramChannel.write(payload)
            } catch (e: IOException) {
                Log.e(TAG, "Error writing UDP packet to destination", e)
                closeUdpSession(session)
            }
        }
    }

    private fun runNetworkSelectorLoop() {
        while (isRunning) {
            try {
                val selectCount = selector.select(1000)
                if (selectCount == 0) {
                    // Check for timed out UDP sessions (inactive for 60 seconds)
                    val now = System.currentTimeMillis()
                    val iterator = udpSessions.values.iterator()
                    while (iterator.hasNext()) {
                        val session = iterator.next()
                        if (now - session.lastActiveTime > 60000) {
                            try {
                                session.datagramChannel.close()
                            } catch (e: Exception) {}
                            iterator.remove()
                        }
                    }
                    continue
                }

                val selectedKeys = selector.selectedKeys()
                val iterator = selectedKeys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()

                    if (!key.isValid) continue

                    if (key.isConnectable) {
                        handleConnectableKey(key)
                    } else if (key.isReadable) {
                        if (key.attachment() is TcpSession) {
                            handleReadableTcpKey(key)
                        } else if (key.attachment() is UdpSession) {
                            handleReadableUdpKey(key)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) break
                Log.e(TAG, "Exception in selector thread", e)
            }
        }
    }

    private fun handleConnectableKey(key: SelectionKey) {
        val session = key.attachment() as TcpSession
        val channel = key.channel() as SocketChannel
        try {
            if (channel.finishConnect()) {
                synchronized(selector) {
                    key.interestOps(SelectionKey.OP_READ)
                }
                log("TCP connected beautifully to real remote: ${session.destIp}:${session.destPort}")
            }
        } catch (e: IOException) {
            log("TCP Connection handshake failed: ${session.destIp}:${session.destPort} error: ${e.message}", showInUi = true)
            sendTcpControlPacket(session, Packet.TCP_FLAG_RST)
            closeTcpSession(session)
        }
    }

    private fun handleReadableTcpKey(key: SelectionKey) {
        val session = key.attachment() as TcpSession
        val channel = key.channel() as SocketChannel
        
        val buffer = ByteBuffer.allocate(16384)
        try {
            val bytesRead = channel.read(buffer)
            if (bytesRead > 0) {
                buffer.flip()
                sendTcpDataPacket(session, buffer)
            } else if (bytesRead < 0) {
                // Remote closed socket gracefully
                sendTcpControlPacket(session, Packet.TCP_FLAG_FIN or Packet.TCP_FLAG_ACK)
                session.mySequenceNum++
                closeTcpSession(session)
            }
        } catch (e: IOException) {
            log("TCP socket read exception: ${e.message}")
            sendTcpControlPacket(session, Packet.TCP_FLAG_RST)
            closeTcpSession(session)
        }
    }

    private fun handleReadableUdpKey(key: SelectionKey) {
        val session = key.attachment() as UdpSession
        val channel = key.channel() as DatagramChannel

        val buffer = ByteBuffer.allocate(16384)
        try {
            val bytesRead = channel.read(buffer)
            if (bytesRead > 0) {
                buffer.flip()
                sendUdpDataPacket(session, buffer)
            }
        } catch (e: IOException) {
            Log.e(TAG, "UDP read error", e)
            closeUdpSession(session)
        }
    }

    private fun closeTcpSession(session: TcpSession) {
        tcpSessions.remove(session.key)
        try {
            session.socketChannel?.close()
        } catch (e: Exception) {}
    }

    private fun closeUdpSession(session: UdpSession) {
        udpSessions.remove(session.key)
        try {
            session.datagramChannel.close()
        } catch (e: Exception) {}
    }

    // Builder helpers to write raw packets back to the VPN tun0 descriptor
    private val tunWriter: FileOutputStream by lazy {
        FileOutputStream(vpnInterface.fileDescriptor)
    }

    @Synchronized
    private fun writeToTun(buf: ByteBuffer) {
        try {
            val limit = buf.limit()
            tunWriter.write(buf.array(), 0, limit)
            LocalVpnService.incrementBytesRouted(limit.toLong())
        } catch (e: IOException) {
            Log.e(TAG, "Error writing back to VPN tun", e)
        }
    }

    private fun sendTcpControlPacket(session: TcpSession, flags: Int) {
        val response = ByteBuffer.allocate(64)
        
        // Build IPv4 Header (20 bytes)
        response.put(0, (4 shl 4 or 5).toByte()) // Version = 4, IHL = 5
        response.put(1, 0.toByte()) // TOS = 0
        response.putShort(2, 40.toShort()) // Total Length = 40 (20 IP + 20 TCP)
        response.putShort(4, 0.toShort()) // ID = 0
        response.putShort(6, 0.toShort()) // Flags & Offset = 0
        response.put(8, 64.toByte()) // TTL = 64
        response.put(9, Packet.PROTOCOL_TCP.toByte()) // Protocol = 6
        response.putShort(10, 0.toShort()) // Reset Header Checksum
        
        // Swap IPs for reverse flow
        val destBin = parseIpLiteral(session.destIp)
        val srcBin = parseIpLiteral(session.clientIp)
        for (i in 0..3) {
            response.put(12 + i, destBin[i])
            response.put(16 + i, srcBin[i])
        }

        // Build TCP Header (20 bytes)
        response.putShort(20, session.destPort.toShort()) // Source Port
        response.putShort(22, session.clientPort.toShort()) // Dest Port
        response.putInt(24, session.mySequenceNum.toInt()) // Sequence Number
        response.putInt(28, session.myAckNum.toInt()) // Acknowledgment Number
        response.putShort(32, (5 shl 12 or flags).toShort()) // Offset = 5, flags
        response.putShort(34, 4096.toShort()) // Window size = 4096
        response.putShort(36, 0.toShort()) // Reset Checksum
        response.putShort(38, 0.toShort()) // Urgent pointer = 0

        response.limit(40)
        
        val pkt = Packet(response)
        pkt.updateIpChecksum()
        pkt.updateTcpChecksum()
        
        writeToTun(response)
    }

    private fun sendTcpDataPacket(session: TcpSession, payload: ByteBuffer) {
        val size = payload.remaining()
        val totalLen = 40 + size
        val response = ByteBuffer.allocate(totalLen)
        
        // Build IPv4 Header
        response.put(0, (4 shl 4 or 5).toByte())
        response.put(1, 0.toByte())
        response.putShort(2, totalLen.toShort())
        response.putShort(4, 0.toShort())
        response.putShort(6, 0.toShort())
        response.put(8, 64.toByte())
        response.put(9, Packet.PROTOCOL_TCP.toByte())
        response.putShort(10, 0.toShort())
        
        val destBin = parseIpLiteral(session.destIp)
        val srcBin = parseIpLiteral(session.clientIp)
        for (i in 0..3) {
            response.put(12 + i, destBin[i])
            response.put(16 + i, srcBin[i])
        }

        // Build TCP Header
        response.putShort(20, session.destPort.toShort())
        response.putShort(22, session.clientPort.toShort())
        response.putInt(24, session.mySequenceNum.toInt())
        response.putInt(28, session.myAckNum.toInt())
        response.putShort(32, (5 shl 12 or Packet.TCP_FLAG_ACK or Packet.TCP_FLAG_PSH).toShort())
        response.putShort(34, 4096.toShort())
        response.putShort(36, 0.toShort())
        response.putShort(38, 0.toShort())

        // Put Payload
        response.position(40)
        response.put(payload)
        response.limit(totalLen)
        
        val pkt = Packet(response)
        pkt.updateIpChecksum()
        pkt.updateTcpChecksum()

        // Update local sequence tracker
        session.mySequenceNum += size
        
        writeToTun(response)
    }

    private fun sendTcpEmptyAckOrRst(incoming: Packet, flags: Int) {
        val response = ByteBuffer.allocate(40)
        
        // Build IPv4
        response.put(0, (4 shl 4 or 5).toByte())
        response.put(1, 0.toByte())
        response.putShort(2, 40.toShort())
        response.putShort(4, 0.toShort())
        response.putShort(6, 0.toShort())
        response.put(8, 64.toByte())
        response.put(9, Packet.PROTOCOL_TCP.toByte())
        response.putShort(10, 0.toShort())
        
        response.put(12, incoming.destinationIP[0])
        response.put(13, incoming.destinationIP[1])
        response.put(14, incoming.destinationIP[2])
        response.put(15, incoming.destinationIP[3])
        response.put(16, incoming.sourceIP[0])
        response.put(17, incoming.sourceIP[1])
        response.put(18, incoming.sourceIP[2])
        response.put(19, incoming.sourceIP[3])

        // Build TCP Header
        response.putShort(20, incoming.destinationPort.toShort())
        response.putShort(22, incoming.sourcePort.toShort())
        
        if (flags == Packet.TCP_FLAG_RST) {
            response.putInt(24, 0)
            response.putInt(28, (incoming.sequenceNumber + incoming.payloadSize + 1).toInt())
        } else {
            response.putInt(24, 1000)
            response.putInt(28, (incoming.sequenceNumber + incoming.payloadSize).toInt())
        }
        
        response.putShort(32, (5 shl 12 or flags).toShort())
        response.putShort(34, 4096.toShort())
        response.putShort(36, 0.toShort())
        response.putShort(38, 0.toShort())

        response.limit(40)
        val pkt = Packet(response)
        pkt.updateIpChecksum()
        pkt.updateTcpChecksum()
        
        writeToTun(response)
    }

    private fun sendUdpDataPacket(session: UdpSession, payload: ByteBuffer) {
        val size = payload.remaining()
        val totalLen = 28 + size // 20 IP + 8 UDP
        val response = ByteBuffer.allocate(totalLen)
        
        // Build IPv4
        response.put(0, (4 shl 4 or 5).toByte())
        response.put(1, 0.toByte())
        response.putShort(2, totalLen.toShort())
        response.putShort(4, 0.toShort())
        response.putShort(6, 0.toShort())
        response.put(8, 64.toByte())
        response.put(9, Packet.PROTOCOL_UDP.toByte())
        response.putShort(10, 0.toShort())
        
        val destBin = parseIpLiteral(session.destIp)
        val srcBin = parseIpLiteral(session.clientIp)
        for (i in 0..3) {
            response.put(12 + i, destBin[i])
            response.put(16 + i, srcBin[i])
        }

        // Build UDP Header
        response.putShort(20, session.destPort.toShort())
        response.putShort(22, session.clientPort.toShort())
        response.putShort(24, (8 + size).toShort()) // UDP Length
        response.putShort(26, 0.toShort()) // Reset UDP Checksum

        response.position(28)
        response.put(payload)
        response.limit(totalLen)

        val pkt = Packet(response)
        pkt.updateIpChecksum()
        pkt.updateUdpChecksum()

        writeToTun(response)
    }

    private fun parseIpLiteral(ipString: String): ByteArray {
        val parts = ipString.split(".")
        val bytes = ByteArray(4)
        for (i in 0..3) {
            bytes[i] = parts[i].toInt().toByte()
        }
        return bytes
    }
}
