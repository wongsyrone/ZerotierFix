package net.kaaass.zerotierfix.service;

import android.util.Log;

import net.kaaass.zerotierfix.util.IPPacketUtils;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;

// TODO: clear up
public class NDPTable {
    public static final String TAG = "NDPTable";
    private static final long ENTRY_TIMEOUT = 120000;

    /** IPv6 头部长度 */
    private static final int IPV6_HEADER_LENGTH = 40;
    /** ICMPv6 报文长度（type + code + checksum + reserved + target + option） */
    private static final int ICMPV6_PAYLOAD_LENGTH = 32;
    /** 整个报文长度 */
    private static final int PACKET_LENGTH = IPV6_HEADER_LENGTH + ICMPV6_PAYLOAD_LENGTH;
    /** IPv6 next header：ICMPv6 */
    private static final int IPPROTO_ICMPV6 = 58;
    /** ICMPv6 type：Neighbor Solicitation */
    private static final int ICMP6_NS = 135;
    /** 旧版布局中写入的 ICMPv6 标记值 */
    private static final int ICMP6_RAW_NS_MARKER = 0x87;
    /** ICMPv6 主体内目标地址相对于 ICMPv6 头的偏移 */
    private static final int ICMP6_TARGET_OFFSET = 8;
    /** ND 选项长度（type 1 + length 1 + 链路层地址 6）*/
    private static final int ND_OPTION_LENGTH = 8;

    /**
     * 计算 IPv6 伪首部的一补和，作为 ICMPv6 校验和的初值。
     * <p>
     * 伪首部包含源地址、目的地址、上层报文长度、三个零字节和下一头部字段。
     * <p>
     * 注意：{@code calculateChecksum} 返回的是取补后的结果，不能直接当作种子，
     * 因此这里自行累加一补和。
     *
     * @param srcAddr 16 字节源地址
     * @param dstAddr 16 字节目的地址
     * @return 伪首部的一补和（已回卷到 16 位）
     */
    private static int ipv6PseudoHeaderSum(byte[] srcAddr, byte[] dstAddr) {
        int sum = 0;
        // 源地址 + 目的地址
        for (int i = 0; i < 16; i += 2) {
            sum += ((srcAddr[i] & 0xFF) << 8) | (srcAddr[i + 1] & 0xFF);
            sum += ((dstAddr[i] & 0xFF) << 8) | (dstAddr[i + 1] & 0xFF);
        }
        // 上层报文长度（4 字节）+ 三个零字节 + 下一头部（1 字节）
        sum += ICMPV6_PAYLOAD_LENGTH;
        sum += IPPROTO_ICMPV6;
        // 回卷
        while ((sum & 0xFFFF0000) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return sum & 0xFFFF;
    }

    private final HashMap<Long, NDPEntry> entriesMap = new HashMap<>();
    private final HashMap<InetAddress, Long> inetAddressToMacAddress = new HashMap<>();
    private final HashMap<InetAddress, NDPEntry> ipEntriesMap = new HashMap<>();
    private final HashMap<Long, InetAddress> macAddressToInetAddress = new HashMap<>();
    private final Thread timeoutThread = new Thread("NDP Timeout Thread") {
        /* class com.zerotier.one.service.NDPTable.AnonymousClass1 */

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    for (NDPEntry nDPEntry : new HashMap<>(NDPTable.this.entriesMap).values()) {
                        if (nDPEntry.getTime() + NDPTable.ENTRY_TIMEOUT < System.currentTimeMillis()) {
                            synchronized (NDPTable.this.macAddressToInetAddress) {
                                NDPTable.this.macAddressToInetAddress.remove(Long.valueOf(nDPEntry.getMac()));
                            }
                            synchronized (NDPTable.this.inetAddressToMacAddress) {
                                NDPTable.this.inetAddressToMacAddress.remove(nDPEntry.getAddress());
                            }
                            synchronized (NDPTable.this.entriesMap) {
                                NDPTable.this.entriesMap.remove(Long.valueOf(nDPEntry.getMac()));
                            }
                            synchronized (NDPTable.this.ipEntriesMap) {
                                NDPTable.this.ipEntriesMap.remove(nDPEntry.getAddress());
                            }
                        }
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    Log.d(NDPTable.TAG, e.toString());
                    return;
                }
            }
        }
    };

    public NDPTable() {
        this.timeoutThread.start();
    }

    /* access modifiers changed from: protected */
    public void stop() {
        try {
            this.timeoutThread.interrupt();
            this.timeoutThread.join();
        } catch (InterruptedException ignored) {
        }
    }

    /* access modifiers changed from: package-private */
    public void setAddress(InetAddress inetAddress, long j) {
        synchronized (this.inetAddressToMacAddress) {
            this.inetAddressToMacAddress.put(inetAddress, Long.valueOf(j));
        }
        synchronized (this.macAddressToInetAddress) {
            this.macAddressToInetAddress.put(Long.valueOf(j), inetAddress);
        }
        NDPEntry nDPEntry = new NDPEntry(j, inetAddress);
        synchronized (this.entriesMap) {
            this.entriesMap.put(Long.valueOf(j), nDPEntry);
        }
        synchronized (this.ipEntriesMap) {
            this.ipEntriesMap.put(inetAddress, nDPEntry);
        }
    }

    /* access modifiers changed from: package-private */
    public boolean hasMacForAddress(InetAddress inetAddress) {
        boolean containsKey;
        synchronized (this.inetAddressToMacAddress) {
            containsKey = this.inetAddressToMacAddress.containsKey(inetAddress);
        }
        return containsKey;
    }

    /* access modifiers changed from: package-private */
    public boolean hasAddressForMac(long j) {
        boolean containsKey;
        synchronized (this.macAddressToInetAddress) {
            containsKey = this.macAddressToInetAddress.containsKey(Long.valueOf(j));
        }
        return containsKey;
    }

    /* access modifiers changed from: package-private */
    public long getMacForAddress(InetAddress inetAddress) {
        synchronized (this.inetAddressToMacAddress) {
            if (!this.inetAddressToMacAddress.containsKey(inetAddress)) {
                return -1;
            }
            long longValue = this.inetAddressToMacAddress.get(inetAddress).longValue();
            updateNDPEntryTime(longValue);
            return longValue;
        }
    }

    /* access modifiers changed from: package-private */
    public InetAddress getAddressForMac(long j) {
        synchronized (this.macAddressToInetAddress) {
            if (!this.macAddressToInetAddress.containsKey(Long.valueOf(j))) {
                return null;
            }
            InetAddress inetAddress = this.macAddressToInetAddress.get(Long.valueOf(j));
            updateNDPEntryTime(inetAddress);
            return inetAddress;
        }
    }

    private void updateNDPEntryTime(InetAddress inetAddress) {
        synchronized (this.ipEntriesMap) {
            NDPEntry nDPEntry = this.ipEntriesMap.get(inetAddress);
            if (nDPEntry != null) {
                nDPEntry.updateTime();
            }
        }
    }

    private void updateNDPEntryTime(long j) {
        synchronized (this.entriesMap) {
            NDPEntry nDPEntry = this.entriesMap.get(Long.valueOf(j));
            if (nDPEntry != null) {
                nDPEntry.updateTime();
            }
        }
    }

    /* access modifiers changed from: package-private */
    public ByteBuffer getNeighborSolicitationPacket(InetAddress source, InetAddress solicit, long localMac) {
        byte[] srcAddr = source.getAddress();
        byte[] solicitAddr = solicit.getAddress();
        byte[] solMacBytes = ByteBuffer.allocate(8).putLong(localMac).array();

        if (srcAddr.length == 16 && solicitAddr.length == 16) {
            // 正常路径：按 RFC 4443 构造 IPv6 + ICMPv6 NS 报文
            return buildNeighborSolicitation(srcAddr, solicitAddr, solMacBytes);
        }

        // 兼容路径：传入非 16 字节地址（如组播 MAC 被当作地址传入）时无法构造
        // 合法的 IPv6 头，退回到旧版布局，避免越界崩溃
        Log.w(TAG, "Unexpected address length, falling back to legacy layout: src="
                + srcAddr.length + " solicit=" + solicitAddr.length);
        return buildLegacyNeighborSolicitation(srcAddr, solicitAddr, solMacBytes);
    }

    /**
     * 构造 IPv6 头 + ICMPv6 Neighbor Solicitation 报文。
     *
     * @param srcAddr      16 字节源地址
     * @param solicitAddr  16 字节待解析地址
     * @param solMacBytes  8 字节链路层地址（大端）
     * @return 直接缓冲区，position 与 limit 均位于报文起点
     */
    private ByteBuffer buildNeighborSolicitation(byte[] srcAddr, byte[] solicitAddr, byte[] solMacBytes) {
        // 报文 = 40 字节 IPv6 头 + 32 字节 ICMPv6 NS
        // 直接缓冲区不支持 array()，无法直接计算校验和，因此先在堆内 byte[] 上构造
        byte[] packet = new byte[PACKET_LENGTH];

        // ---- IPv6 头 ----
        packet[0] = (byte) 0x60; // version=6, traffic class=0
        packet[1] = 0; // flow label
        packet[2] = 0;
        packet[3] = 0;
        packet[4] = 0; // payload length = 32
        packet[5] = (byte) ICMPV6_PAYLOAD_LENGTH;
        packet[6] = (byte) IPPROTO_ICMPV6; // next header
        packet[7] = (byte) 0xFF; // hop limit, unset
        System.arraycopy(srcAddr, 0, packet, 8, 16);
        System.arraycopy(solicitAddr, 0, packet, 24, 16);

        // ---- ICMPv6 头 ----
        packet[IPV6_HEADER_LENGTH] = (byte) ICMP6_NS; // type = 135
        packet[IPV6_HEADER_LENGTH + 1] = 0; // code = 0
        packet[IPV6_HEADER_LENGTH + 2] = 0; // checksum, 先置 0
        packet[IPV6_HEADER_LENGTH + 3] = 0;

        // ---- ICMPv6 主体：目标地址 + ND 选项（源链路层地址）----
        // 主体共 32 字节 = 头 8 + 目标地址 16 + 选项 8，恰好用满
        System.arraycopy(solicitAddr, 0, packet, IPV6_HEADER_LENGTH + ICMP6_TARGET_OFFSET, 16);
        int option = PACKET_LENGTH - ND_OPTION_LENGTH; // 64
        packet[option] = 1; // option type = source link-layer address
        packet[option + 1] = 1; // option length = 1（8 字节）
        // 链路层地址取 MAC 的后 6 字节
        System.arraycopy(solMacBytes, 2, packet, option + 2, 6);

        // ---- ICMPv6 校验和 ----
        // ICMPv6 校验和需包含 IPv6 伪首部，否则对端会丢弃报文
        int checksum = (int) IPPacketUtils.calculateChecksum(
                packet, ipv6PseudoHeaderSum(srcAddr, solicitAddr), IPV6_HEADER_LENGTH, PACKET_LENGTH);
        packet[IPV6_HEADER_LENGTH + 2] = (byte) (checksum >> 8);
        packet[IPV6_HEADER_LENGTH + 3] = (byte) checksum;

        // 转换为直接缓冲区，position/limit 均置于报文起点
        ByteBuffer buffer = ByteBuffer.allocateDirect(PACKET_LENGTH);
        buffer.put(packet);
        buffer.rewind();
        return buffer;
    }

    /**
     * 旧版报文布局，字段与字节流解析保持一致；仅在地址长度异常时使用。
     *
     * @param srcAddr      源地址字节（长度可能不足 16）
     * @param solicitAddr  待解析地址字节（长度可能不足 16）
     * @param solMacBytes  8 字节链路层地址（大端）
     * @return 直接缓冲区，position 与 limit 均位于报文起点
     */
    private ByteBuffer buildLegacyNeighborSolicitation(byte[] srcAddr, byte[] solicitAddr, byte[] solMacBytes) {
        byte[] packet = new byte[PACKET_LENGTH];
        copyInto(packet, 0, srcAddr, 16);
        copyInto(packet, 16, solicitAddr, 16);
        packet[32] = 0;
        packet[33] = 0;
        packet[34] = 0;
        packet[35] = 32;
        packet[36] = (byte) IPPROTO_ICMPV6;
        packet[37] = (byte) ICMP6_RAW_NS_MARKER;
        copyInto(packet, 38, solicitAddr, 16);
        packet[54] = 1;
        packet[55] = 1;
        System.arraycopy(solMacBytes, 2, packet, 56, 6);

        int checksum = (int) IPPacketUtils.calculateChecksum(packet, 0, 0, PACKET_LENGTH);
        packet[42] = (byte) (checksum >> 8);
        packet[43] = (byte) checksum;

        packet[0] = (byte) 0x60;
        packet[4] = 0;
        packet[5] = (byte) ICMPV6_PAYLOAD_LENGTH;
        packet[6] = (byte) IPPROTO_ICMPV6;
        packet[7] = (byte) 0xFF;
        copyInto(packet, 8, srcAddr, 16);
        copyInto(packet, 24, solicitAddr, 16);

        ByteBuffer buffer = ByteBuffer.allocateDirect(PACKET_LENGTH);
        buffer.put(packet);
        buffer.rewind();
        return buffer;
    }

    /**
     * 将地址字节拷贝到指定偏移，超长部分截断，不足部分以 0 填充。
     *
     * @param packet 目标报文
     * @param offset 目标偏移
     * @param addr   地址字节
     * @param width  目标字段宽度
     */
    private static void copyInto(byte[] packet, int offset, byte[] addr, int width) {
        int len = Math.min(width, addr.length);
        System.arraycopy(addr, 0, packet, offset, len);
    }

}
