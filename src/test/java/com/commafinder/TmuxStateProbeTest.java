package com.commafinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TmuxStateProbeTest {
    @Test
    public void detectsTmuxStartedInsideForegroundScript() {
        assertEquals(Boolean.TRUE, TmuxStateProbe.parse(
                "Ss bash\nS+ gitpull.sh\nS+ tmux: client\n"));
        assertEquals(Boolean.TRUE, TmuxStateProbe.parse("Ss bash\nS+ tmux\n"));
    }

    @Test
    public void returnsToShellAfterDetachOrExit() {
        assertEquals(Boolean.TRUE, TmuxStateProbe.parse("Ss bash\nS+ tmux: client\n"));
        assertEquals(Boolean.FALSE, TmuxStateProbe.parse("Ss+ bash\n"));
    }

    @Test
    public void doesNotConfuseOtherFullScreenAppsOrBackgroundTmuxWithAttachment() {
        assertEquals(Boolean.FALSE, TmuxStateProbe.parse("Ss bash\nS+ vim\n"));
        assertEquals(Boolean.FALSE, TmuxStateProbe.parse("Ss+ bash\nS tmux: client\n"));
        assertEquals(Boolean.FALSE, TmuxStateProbe.parse("Ss+ bash\nT+ tmux: client\n"));
        assertEquals(Boolean.FALSE, TmuxStateProbe.parse("Ss+ bash\nS+ tmux: server\n"));
    }

    @Test
    public void failedAttachOrScriptWithoutTmuxLeavesShellMode() {
        assertEquals(Boolean.FALSE, TmuxStateProbe.parse("Ss+ bash\n"));
        assertEquals(Boolean.FALSE, TmuxStateProbe.parse("Ss bash\nS+ gitpull.sh\nS+ git\n"));
    }

    @Test
    public void incompleteProbeDoesNotForceShellMode() {
        assertNull(TmuxStateProbe.parse(""));
        assertNull(TmuxStateProbe.parse("ps: unsupported option\n"));
        assertNull(TmuxStateProbe.parse("STAT COMMAND\n"));
    }
}
