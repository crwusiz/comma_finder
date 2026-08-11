package com.commafinder;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionUtilsTest {

    @Test
    public void comparesNumericVersions() {
        assertTrue(VersionUtils.isUpdateAvailable("1.9", "1.10"));
        assertTrue(VersionUtils.isUpdateAvailable("1.0.9", "1.1"));
        assertFalse(VersionUtils.isUpdateAvailable("1.2", "1.2.0"));
        assertFalse(VersionUtils.isUpdateAvailable("2.0", "1.99"));
    }

    @Test
    public void handlesInvalidOrMissingVersions() {
        assertTrue(VersionUtils.isUpdateAvailable("1.0-beta", "1.0"));
        assertFalse(VersionUtils.isUpdateAvailable(null, "1.0"));
        assertFalse(VersionUtils.isUpdateAvailable("1.0", null));
    }
}
