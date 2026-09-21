package com.commafinder;

/** Read-only probe for the Linux/OpenSSH terminal used by this SSH connection. */
final class TmuxStateProbe {
    private TmuxStateProbe() {}

    // OpenSSH starts shell and exec channels as children of the same session process.
    // Only the shell channel has a PTY. Do not inspect other SSH users' terminals or
    // rely on the default tmux socket (scripts may use a custom socket).
    static final String COMMAND = """
            parent=$PPID
            terminals=$(LC_ALL=C ps -o tty= --ppid "$parent") || exit 2
            terminal=
            for candidate in $terminals; do
              case "$candidate" in
                pts/[0-9]*|tty[0-9]*)
                  [ -z "$terminal" ] || exit 2
                  terminal=$candidate ;;
              esac
            done
            [ -n "$terminal" ] || exit 2
            LC_ALL=C ps -t "$terminal" -o stat= -o comm=
            """;

    /** null means no reliable snapshot; retain the previous state in that case. */
    static Boolean parse(String output) {
        boolean foundProcess = false;
        for (String line : output.split("\n")) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length != 2 || !fields[0].matches("[RSDTtZXIWPK][<NLsl+]*")) {
                continue;
            }
            foundProcess = true;
            String state = fields[0];
            String command = fields[1].trim();
            if (state.contains("+") && "TtZX".indexOf(state.charAt(0)) < 0
                    && (command.equals("tmux") || command.equals("tmux: client"))) {
                return true;
            }
        }
        return foundProcess ? Boolean.FALSE : null;
    }
}
