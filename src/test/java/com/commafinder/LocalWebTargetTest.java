package com.commafinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LocalWebTargetTest {

    @Test
    public void acceptsCanonicalAddressesInEveryPrivateRange() {
        for (String ip : new String[] {
                "10.0.0.1", "10.255.255.254", "172.16.0.1",
                "172.18.1.2", "172.31.255.254", "192.168.0.1", "192.168.255.254"
        }) {
            LocalWebTarget target = LocalWebTarget.fromIp(ip);
            assertEquals(ip, target.ip());
            assertEquals("http://" + ip + ":7000/", target.rootUrl());
        }
    }

    @Test
    public void rejectsNonPrivateAndAmbiguousAddresses() {
        for (String ip : new String[] {
                null, "", "8.8.8.8", "127.0.0.1", "0.0.0.0", "169.254.1.2",
                "172.15.255.255", "172.32.0.1", "192.167.1.2", "192.169.1.2",
                "localhost", "192.168.1", "192.168.1.256", "192.168.1.-1",
                "192.168.1.01", "0300.0250.1.1", "0xc0.0xa8.1.1", "3232235777",
                "+192.168.1.1", "192.168.+1.1", " 192.168.1.1", "192.168.1.1\n",
                "%31%39%32.168.1.1", "192.168.1.1:7000", "http://192.168.1.1",
                "192.168.1.1@evil.example", "::ffff:192.168.1.1", "１９２.168.1.1"
        }) {
            assertThrows(String.valueOf(ip), IllegalArgumentException.class,
                    () -> LocalWebTarget.fromIp(ip));
        }
    }

    @Test
    public void allowsPathsQueriesAndFragmentsOnTheExactOrigin() {
        LocalWebTarget target = LocalWebTarget.fromIp("192.168.1.20");
        for (String url : new String[] {
                "http://192.168.1.20:7000", "HTTP://192.168.1.20:7000/",
                "http://192.168.1.20:7000/files/log%20one?download=1#details",
                "http://192.168.1.20:7000/?next=https://example.com/",
                "http://192.168.1.20:7000//example.com/path"
        }) {
            assertTrue(url, target.isSameOrigin(url));
        }
    }

    @Test
    public void rejectsDifferentOriginsAndAuthorityBypasses() {
        LocalWebTarget target = LocalWebTarget.fromIp("192.168.1.20");
        for (String url : new String[] {
                null, "", "/files", "//192.168.1.20:7000/",
                "https://192.168.1.20:7000/", "http://192.168.1.20/",
                "http://192.168.1.20:80/", "http://192.168.1.20:7001/",
                "http://192.168.1.21:7000/", "http://8.8.8.8:7000/",
                "http://127.0.0.1:7000/", "http://localhost:7000/",
                "http://192.168.1.20.evil.example:7000/",
                "http://192.168.1.20:7000@evil.example/",
                "http://evil.example@192.168.1.20:7000/",
                "http://user:password@192.168.1.20:7000/",
                "http://192.168.1.20.:7000/", "http://192.168.001.20:7000/",
                "http://%31%39%32.168.1.20:7000/", "http://3232235796:7000/",
                "http://0xc0a80114:7000/", "http://192.168.1.20:07000/",
                "http://192.168.1.20:7000\\@evil.example/",
                "http://192.168.1.20:7000\n/", "http://192.168.1.20:7000/%",
                "file:///192.168.1.20:7000/", "content://192.168.1.20:7000/",
                "javascript:alert(1)", "data:text/html,hello", "intent://192.168.1.20:7000/"
        }) {
            assertFalse(String.valueOf(url), target.isSameOrigin(url));
        }
    }

    @Test
    public void allowsOnlyValidHttpAndHttpsExternalUrlsWithoutUserInfo() {
        for (String url : new String[] {
                "https://example.com/path?q=1#part", "HTTP://example.com/",
                "http://192.168.1.20:7000/", "https://example.com:8443/",
                "http://[2001:db8::1]:7000/"
        }) {
            assertTrue(url, LocalWebTarget.isExternalWebUrl(url));
        }
        for (String url : new String[] {
                null, "", "example.com", "//example.com/", "/relative",
                "http:///path", "https://", "https:example.com", "https://bad host/",
                "https://example.com:65536/", "https://example.com:-1/",
                "https://example.com:/", "https://example.com:abc/",
                "https://user@example.com/", "https://user:secret@example.com/",
                "https://@example.com/", "https://%65xample.com/", "https://example.com/%",
                "file:///tmp/file.html", "content://files/test", "javascript:alert(1)",
                "data:text/html,hello", "intent://example.com/", "ftp://example.com/"
        }) {
            assertFalse(String.valueOf(url), LocalWebTarget.isExternalWebUrl(url));
        }
    }
}
