package net.kaaass.zerotierfix.util;

import android.util.Log;

import org.apache.commons.validator.routines.InetAddressValidator;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class InetAddressUtils {
    public static final String TAG = "InetAddressUtils";

    public static final long BROADCAST_MAC_ADDRESS = 0xffffffffffffL;

    private static final InetAddressValidator VALIDATOR = InetAddressValidator.getInstance();

    /**
     * 获得地址指定 CIDR 的子网掩码
     */
    public static byte[] addressToNetmask(InetAddress address, int cidr) {
        long j;
        int length = address.getAddress().length * 8;
        if (address instanceof Inet4Address) {
            int subnetLength = length - cidr;
            return ByteBuffer.allocate(4).putInt(subnetLength == 32 ? 0 : -1 << subnetLength).array();
        } else if (address instanceof Inet6Address) {
            int subnetLength = length - cidr;
            long j2 = 0;
            if (subnetLength == 128) {
                j = 0;
            } else if (subnetLength >= 64) {
                j = 0;
                j2 = -1 << (subnetLength - 64);
            } else {
                j = -1 << subnetLength;
                j2 = -1;
            }
            return ByteBuffer.allocate(16).putLong(j2).putLong(j).array();
        } else {
            throw new RuntimeException("Unreachable");
        }
    }

    public static InetAddress addressToRoute(InetAddress inetAddress, int i) {
        if (i == 0) {
            if (inetAddress instanceof Inet4Address) {
                try {
                    return Inet4Address.getByAddress(new byte[4]);
                } catch (UnknownHostException unused) {
                    return null;
                }
            } else if (inetAddress instanceof Inet6Address) {
                try {
                    return Inet6Address.getByAddress(new byte[16]);
                } catch (UnknownHostException unused2) {
                    return null;
                }
            }
        }
        return addressToRouteNo0Route(inetAddress, i);
    }

    /**
     * 获得地址对应的网络前缀
     */
    public static InetAddress addressToRouteNo0Route(InetAddress address, int cidr) {
        byte[] netmask = addressToNetmask(address, cidr);
        byte[] rawAddress = new byte[netmask.length];
        for (int i = 0; i < netmask.length; i++) {
            rawAddress[i] = (byte) (address.getAddress()[i] & netmask[i]);
        }
        try {
            return InetAddress.getByAddress(rawAddress);
        } catch (UnknownHostException unused) {
            Log.e(TAG, "Unknown Host Exception calculating route", unused);
            return null;
        }
    }

    public static long ipv6ToMulticastAddress(InetAddress inetAddress) {
        byte[] address = inetAddress.getAddress();
        if (address.length != 16) {
            return 0;
        }
        return ByteBuffer.wrap(new byte[]{0, 0, 51, 51, -1, address[13], address[14], address[15]}).getLong();
    }

    /**
     * 校验是否为合法的 IPv4 地址，空字符串视为合法（表示交由系统解析）
     */
    public static boolean isValidIPv4(String str) {
        return str.isEmpty() || VALIDATOR.isValidInet4Address(str);
    }

    /**
     * 校验是否为合法的 IPv6 地址，空字符串视为合法（表示交由系统解析）
     */
    public static boolean isValidIPv6(String str) {
        return str.isEmpty() || VALIDATOR.isValidInet6Address(str);
    }
}
