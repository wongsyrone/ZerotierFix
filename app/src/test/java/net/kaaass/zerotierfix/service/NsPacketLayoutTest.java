package net.kaaass.zerotierfix.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * 校验 {@link NDPTable#getNeighborSolicitationPacket} 生成的报文结构。
 * <p>
 * 报文为 40 字节 IPv6 头 + 32 字节 ICMPv6 Neighbor Solicitation，
 * 参考 RFC 4443 第 4.3 节。
 */
public class NsPacketLayoutTest {

    private static final int PACKET_LENGTH = 72;
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int IPPROTO_ICMPV6 = 58;
    private static final int ICMP6_NS = 135;
    private static final long MAC = 0x02aabbccddeeffL;

    private static byte[] addr(int base) {
        byte[] a = new byte[16];
        for (int i = 0; i < 16; i++) {
            a[i] = (byte) (base + i);
        }
        return a;
    }

    private static byte[] packet(byte[] src, byte[] dst) throws Exception {
        NDPTable table = new NDPTable();
        try {
            ByteBuffer buf = table.getNeighborSolicitationPacket(
                    InetAddress.getByAddress(src), InetAddress.getByAddress(dst), MAC);
            assertTrue("buffer must be direct for GetDirectBufferAddress", buf.isDirect());
            byte[] out = new byte[buf.remaining()];
            buf.get(out);
            return out;
        } finally {
            table.stop();
        }
    }

    private static byte[] slice(byte[] a, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(a, off, r, 0, len);
        return r;
    }

    @Test
    public void packetHasExpectedLength() throws Exception {
        assertEquals(PACKET_LENGTH,
                packet(addr(0xfd), addr(0x20)).length);
    }

    @Test
    public void ipv6HeaderIsWellFormed() throws Exception {
        byte[] src = addr(0xfd);
        byte[] dst = addr(0x20);
        byte[] p = packet(src, dst);

        assertEquals("version must be 6", 6, p[0] >> 4);
        assertEquals("payload length must be 32", 32, ((p[4] & 0xFF) << 8) | (p[5] & 0xFF));
        assertEquals("next header must be ICMPv6", IPPROTO_ICMPV6, p[6]);
        assertArrayEquals("source address at offset 8", src, slice(p, 8, 16));
        assertArrayEquals("destination address at offset 24", dst, slice(p, 24, 16));
    }

    @Test
    public void icmpv6HeaderIsWellFormed() throws Exception {
        byte[] dst = addr(0x20);
        byte[] p = packet(addr(0xfd), dst);

        assertEquals("type must be 135 (Neighbor Solicitation)", ICMP6_NS, p[IPV6_HEADER_LENGTH] & 0xFF);
        assertEquals("code must be 0", 0, p[IPV6_HEADER_LENGTH + 1] & 0xFF);
        assertArrayEquals("target address at offset 48", dst, slice(p, 48, 16));
    }

    @Test
    public void ndOptionCarriesTheLinkLayerAddress() throws Exception {
        byte[] p = packet(addr(0xfd), addr(0x20));

        assertEquals("option type must be 1 (source link-layer address)", 1, p[64] & 0xFF);
        assertEquals("option length must be 1", 1, p[65] & 0xFF);
        assertArrayEquals("link-layer address must be the node MAC",
                new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
                        (byte) 0xdd, (byte) 0xee, (byte) 0xff},
                slice(p, 66, 6));
    }

    @Test
    public void checksumCoversTheIpv6PseudoHeader() throws Exception {
        byte[] src = addr(0xfd);
        byte[] dst = addr(0x20);
        byte[] p = packet(src, dst);

        int sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += ((src[i] & 0xFF) << 8) | (src[i + 1] & 0xFF);
            sum += ((dst[i] & 0xFF) << 8) | (dst[i + 1] & 0xFF);
        }
        sum += 32; // upper-layer packet length
        sum += IPPROTO_ICMPV6;
        while ((sum & 0xFFFF0000) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        for (int i = IPV6_HEADER_LENGTH; i < PACKET_LENGTH; i += 2) {
            sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
            while ((sum & 0xFFFF0000) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
        }
        assertEquals("pseudo-header + ICMPv6 must sum to 0xFFFF", 0xFFFF, sum & 0xFFFF);
    }
}