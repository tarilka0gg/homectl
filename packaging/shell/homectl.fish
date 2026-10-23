# homectl shell part: puts every interactive fish under homectl-pty so its window can be mirrored in the app.
# Install: copy to ~/.config/fish/conf.d/homectl.fish. Opt out for one shell: set HOMECTL_NO_MIRROR=1. Disable all: HOMECTL_NO_TERMINAL=1 in the daemon.

function homectl-attach -d "Mirror this terminal in the homectl app (restarts this shell inside the wrapper, keeping cwd and exported variables)"
    set -q HOMECTL_WINDOW; and return 0 # already mirrored: say nothing
    if not command -q homectl-pty
        echo "homectl-pty not found in PATH" >&2; return 1
    end
    exec homectl-pty -- (status fish-path)
end

# never a login shell (the wrapper starts a plain fish, so a login script such as "start the compositor on tty1" would never run),
# and only inside a terminal window, not on a Linux console
if status is-interactive; and not status is-login; and string match -q "/dev/pts/*" (tty); and not set -q HOMECTL_WINDOW; and not set -q HOMECTL_NO_MIRROR; and command -q homectl-pty
    homectl-attach
end
