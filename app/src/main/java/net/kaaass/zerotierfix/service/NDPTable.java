package net.kaaass.zerotierfix.service;

import android.util.Log;

import net.kaaass.zerotierfix.util.IPPacketUtils;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

// TODO: clear up
public class NDPTable {
    public static final String TAG = "NDPTable";
    private static final long ENTRY_TIMEOUT = 120000;
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
    public ByteBuffer getNeighborSolicitationPacket(InetAddress inetAddress, InetAddress inetAddress2, long j) {
        // 先在堆内 byte[] 上构造报文：直接缓冲区不支持 array()，无法直接计算校验和
        byte[] packet = new byte[72];
        // 源地址
        System.arraycopy(inetAddress.getAddress(), 0, packet, 0, 16);
        // 目标地址
        System.arraycopy(inetAddress2.getAddress(), 0, packet, 16, 16);
        // 跳数限制 32
        packet[32] = 0;
        packet[33] = 0;
        packet[34] = 0;
        packet[35] = 32;
        // ICMPv6 类型（135 请求）与代码（0）
        packet[36] = 58;
        packet[37] = -121;
        // 再拷贝一次目标地址
        System.arraycopy(inetAddress2.getAddress(), 0, packet, 38, 16);
        // 保留字段
        packet[54] = 1;
        packet[55] = 1;
        // 链路层地址（取 MAC 的后 6 字节）
        byte[] macBytes = ByteBuffer.allocate(8).putLong(j).array();
        System.arraycopy(macBytes, 2, packet, 56, 6);

        // 计算 ICMPv6 校验和
        short checksum = (short) IPPacketUtils.calculateChecksum(packet, 0, 0, 72);
        packet[42] = (byte) (checksum >> 8);
        packet[43] = (byte) checksum;

        // 报文头部
        Arrays.fill(packet, 0, 40, (byte) 0);
        packet[0] = (byte) 96;
        packet[4] = 0;
        packet[5] = 32;
        packet[6] = 58;
        packet[7] = -1;
        System.arraycopy(inetAddress.getAddress(), 0, packet, 8, 16);
        System.arraycopy(inetAddress2.getAddress(), 0, packet, 24, 16);

        // 转换为直接缓冲区，position/limit 均置于报文起点
        ByteBuffer buffer = ByteBuffer.allocateDirect(72);
        buffer.put(packet);
        buffer.rewind();
        return buffer;
    }

}
