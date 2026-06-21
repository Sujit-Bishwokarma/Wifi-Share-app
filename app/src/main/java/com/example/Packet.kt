package com.example

import java.nio.ByteBuffer

class Packet(val buffer: ByteBuffer) {
    // IPv4 Header Fields
    var version: Int = 0
    var ihl: Int = 0 // Internet Header Length (in 32-bit words)
    var tos: Int = 0
    var totalLength: Int = 0
    var identification: Int = 0
    var flags: Int = 0
    var fragmentOffset: Int = 0
    var ttl: Int = 0
    var protocol: Int = 0
    var headerChecksum: Int = 0
    var sourceIP: ByteArray = ByteArray(4)
    var destinationIP: ByteArray = ByteArray(4)

    // Transport Layer protocol offset
    var transportOffset: Int = 0

    // TCP Header Fields (if protocol is TCP)
    var sourcePort: Int = 0
    var destinationPort: Int = 0
    var sequenceNumber: Long = 0
    var ackNumber: Long = 0
    var dataOffset: Int = 0 // TCP header length (in 32-bit words)
    var tcpFlags: Int = 0
    var windowSize: Int = 0
    var tcpChecksum: Int = 0
    var urgentPointer: Int = 0

    // TCP Flags constants
    companion object {
        const val TCP_FLAG_FIN = 0x01
        const val TCP_FLAG_SYN = 0x02
        const val TCP_FLAG_RST = 0x04
        const val TCP_FLAG_PSH = 0x08
        const val TCP_FLAG_ACK = 0x10
        const val TCP_FLAG_URG = 0x20

        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
    }

    // UDP Header Fields (if protocol is UDP)
    var udpLength: Int = 0
    var udpChecksum: Int = 0

    // Payload Fields
    var payloadOffset: Int = 0
    var payloadSize: Int = 0

    init {
        try {
            parseIPv4()
        } catch (e: Exception) {
            version = 0
            protocol = 0
        }
    }

    private fun parseIPv4() {
        buffer.rewind()
        if (buffer.remaining() < 20) {
            version = 0
            return
        }
        val verIhl = buffer.get().toInt() and 0xFF
        version = verIhl shr 4
        if (version != 4) {
            return
        }
        ihl = verIhl and 0x0F
        val minIhlBytes = ihl * 4
        if (minIhlBytes < 20 || buffer.remaining() < (minIhlBytes - 1)) {
            return
        }
        tos = buffer.get().toInt() and 0xFF
        totalLength = buffer.getShort().toInt() and 0xFFFF
        identification = buffer.getShort().toInt() and 0xFFFF
        
        val flagsFrag = buffer.getShort().toInt() and 0xFFFF
        flags = flagsFrag shr 13
        fragmentOffset = flagsFrag and 0x1FFF
        
        ttl = buffer.get().toInt() and 0xFF
        protocol = buffer.get().toInt() and 0xFF
        headerChecksum = buffer.getShort().toInt() and 0xFFFF
        
        if (buffer.remaining() < 8) return
        buffer.get(sourceIP)
        buffer.get(destinationIP)

        transportOffset = minIhlBytes
        if (transportOffset > totalLength || transportOffset > buffer.limit()) {
            return
        }
        buffer.position(transportOffset)

        if (protocol == PROTOCOL_TCP) {
            parseTCP()
        } else if (protocol == PROTOCOL_UDP) {
            parseUDP()
        }
    }

    private fun parseTCP() {
        if (buffer.remaining() < 20) return
        sourcePort = buffer.getShort().toInt() and 0xFFFF
        destinationPort = buffer.getShort().toInt() and 0xFFFF
        sequenceNumber = buffer.getInt().toLong() and 0xFFFFFFFFL
        ackNumber = buffer.getInt().toLong() and 0xFFFFFFFFL
        
        val dataOffsetFlags = buffer.getShort().toInt() and 0xFFFF
        dataOffset = dataOffsetFlags shr 12
        tcpFlags = dataOffsetFlags and 0x3F
        windowSize = buffer.getShort().toInt() and 0xFFFF
        tcpChecksum = buffer.getShort().toInt() and 0xFFFF
        urgentPointer = buffer.getShort().toInt() and 0xFFFF

        payloadOffset = transportOffset + (dataOffset * 4)
        if (payloadOffset > totalLength || payloadOffset > buffer.limit()) {
            payloadSize = 0
        } else {
            payloadSize = totalLength - payloadOffset
        }
        if (payloadSize < 0) payloadSize = 0
    }

    private fun parseUDP() {
        if (buffer.remaining() < 8) return
        sourcePort = buffer.getShort().toInt() and 0xFFFF
        destinationPort = buffer.getShort().toInt() and 0xFFFF
        udpLength = buffer.getShort().toInt() and 0xFFFF
        udpChecksum = buffer.getShort().toInt() and 0xFFFF

        payloadOffset = transportOffset + 8
        if (payloadOffset > totalLength || payloadOffset > buffer.limit()) {
            payloadSize = 0
        } else {
            payloadSize = totalLength - payloadOffset
        }
        if (payloadSize < 0) payloadSize = 0
    }

    fun sourceIpToString(): String {
        return "${sourceIP[0].toInt() and 0xFF}.${sourceIP[1].toInt() and 0xFF}.${sourceIP[2].toInt() and 0xFF}.${sourceIP[3].toInt() and 0xFF}"
    }

    fun destIpToString(): String {
        return "${destinationIP[0].toInt() and 0xFF}.${destinationIP[1].toInt() and 0xFF}.${destinationIP[2].toInt() and 0xFF}.${destinationIP[3].toInt() and 0xFF}"
    }

    fun swapAddressesAndPorts() {
        val tempIp = sourceIP.clone()
        sourceIP = destinationIP
        destinationIP = tempIp

        val tempPort = sourcePort
        sourcePort = destinationPort
        destinationPort = tempPort
    }

    fun updateIpChecksum() {
        buffer.position(0)
        // Reset checksum field for calculation
        buffer.putShort(10, 0)
        
        var sum = 0
        val headerLength = ihl * 4
        buffer.position(0)
        for (i in 0 until headerLength step 2) {
            sum += buffer.getShort(i).toInt() and 0xFFFF
        }
        
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        
        headerChecksum = (sum.inv()) and 0xFFFF
        buffer.putShort(10, headerChecksum.toShort())
    }

    fun updateTcpChecksum() {
        buffer.position(transportOffset)
        // Reset tcp checksum field for calculation
        buffer.putShort(transportOffset + 16, 0)

        var sum = 0
        // Pseudo header: Src IP (4 bytes), Dst IP (4 bytes), Reserved (1 byte), Protocol (1 byte), TCP Length (2 bytes)
        val pseudoHeader = ByteBuffer.allocate(12)
        pseudoHeader.put(sourceIP)
        pseudoHeader.put(destinationIP)
        pseudoHeader.put(0.toByte())
        pseudoHeader.put(PROTOCOL_TCP.toByte())
        
        val tcpLength = totalLength - transportOffset
        pseudoHeader.putShort(tcpLength.toShort())
        
        pseudoHeader.rewind()
        for (i in 0 until 12 step 2) {
            sum += pseudoHeader.getShort(i).toInt() and 0xFFFF
        }

        buffer.position(transportOffset)
        for (i in 0 until tcpLength step 2) {
            if (i == tcpLength - 1) {
                // Odd last byte
                sum += (buffer.get(transportOffset + i).toInt() and 0xFF) shl 8
            } else {
                sum += buffer.getShort(i + transportOffset).toInt() and 0xFFFF
            }
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        tcpChecksum = (sum.inv()) and 0xFFFF
        buffer.putShort(transportOffset + 16, tcpChecksum.toShort())
    }

    fun updateUdpChecksum() {
        buffer.position(transportOffset)
        // Reset udp checksum field for calculation
        buffer.putShort(transportOffset + 6, 0)

        var sum = 0
        // Pseudo header: Src IP (4 bytes), Dst IP (4 bytes), Reserved (1 byte), Protocol (1 byte), UDP Length (2 bytes)
        val pseudoHeader = ByteBuffer.allocate(12)
        pseudoHeader.put(sourceIP)
        pseudoHeader.put(destinationIP)
        pseudoHeader.put(0.toByte())
        pseudoHeader.put(PROTOCOL_UDP.toByte())
        
        val udpLen = totalLength - transportOffset
        pseudoHeader.putShort(udpLen.toShort())
        
        pseudoHeader.rewind()
        for (i in 0 until 12 step 2) {
            sum += pseudoHeader.getShort(i).toInt() and 0xFFFF
        }

        buffer.position(transportOffset)
        for (i in 0 until udpLen step 2) {
            if (i == udpLen - 1) {
                // Odd last byte
                sum += (buffer.get(transportOffset + i).toInt() and 0xFF) shl 8
            } else {
                sum += buffer.getShort(i + transportOffset).toInt() and 0xFFFF
            }
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        udpChecksum = (sum.inv()) and 0xFFFF
        buffer.putShort(transportOffset + 6, udpChecksum.toShort())
    }
}
