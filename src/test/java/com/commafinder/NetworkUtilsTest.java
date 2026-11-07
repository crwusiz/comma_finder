package com.commafinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NetworkUtilsTest {

    @Test
    public void detectsPrivateIpv4Ranges() {
        assertTrue(NetworkUtils.isPrivateIpv4("10.0.0.1"));
        assertTrue(NetworkUtils.isPrivateIpv4("172.16.0.1"));
        assertTrue(NetworkUtils.isPrivateIpv4("172.31.255.254"));
        assertTrue(NetworkUtils.isPrivateIpv4("192.168.1.2"));
        assertFalse(NetworkUtils.isPrivateIpv4("172.32.0.1"));
        assertFalse(NetworkUtils.isPrivateIpv4("8.8.8.8"));
        assertFalse(NetworkUtils.isPrivateIpv4("192.168.1"));
        assertFalse(NetworkUtils.isPrivateIpv4("192.168.1.999"));
    }

    @Test
    public void extractsOnlyValidSubnets() {
        assertEquals("192.168.42", NetworkUtils.subnetOf("192.168.42.7"));
        assertNull(NetworkUtils.subnetOf("192.168.42"));
        assertNull(NetworkUtils.subnetOf("not-an-ip"));
    }

    @Test
    public void vpnAndHotspotLabelsTakePriority() {
        assertEquals("VPN", NetworkUtils.networkLabel("tun0", "10.0.0.2", ""));
        assertEquals("핫스팟", NetworkUtils.networkLabel("swlan0", "192.168.1.1", ""));
        assertEquals("Wi-Fi - Home", NetworkUtils.networkLabel("wlan0", "192.168.1.2", " - Home"));
    }

    @Test
    public void private17218LanIsNotExcludedAsVpn() {
        assertEquals("Wi-Fi - Home", NetworkUtils.networkLabel("wlan0", "172.18.1.2", " - Home"));
        assertEquals("핫스팟", NetworkUtils.networkLabel("swlan0", "172.18.1.1", ""));
        assertEquals("VPN", NetworkUtils.networkLabel("tun0", "172.18.1.2", ""));
        assertEquals("VPN", NetworkUtils.networkLabel("tap0", "172.18.1.2", ""));
    }
}
