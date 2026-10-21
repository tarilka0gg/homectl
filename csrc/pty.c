/* Spawn a shell on a pseudo-terminal for the web terminal. */
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

extern char **environ;

/* Returns the master fd (non-blocking) or -1; child pid in *pid_out. Everything the child needs is
 * prepared before fork() so it only calls async-signal-safe functions before exec. */
int hc_pty_spawn(int cols, int rows, const char *shell, const char *home, int *pid_out) {
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    int n = 0;
    while (environ[n]) n++;
    char **envp = calloc(n + 3, sizeof *envp);
    if (!envp) return -1;
    int k = 0;
    for (int i = 0; i < n; i++)
        if (strncmp(environ[i], "TERM=", 5) && strncmp(environ[i], "COLORTERM=", 10)) envp[k++] = environ[i];
    envp[k++] = "TERM=xterm-256color";
    envp[k++] = "COLORTERM=truecolor";
    char *argv[] = { (char *)shell, "-i", NULL };

    int master;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) { free(envp); return -1; }
    if (pid == 0) {
        if (home && chdir(home)) { /* stay where we are */ }
        execve(shell, argv, envp);
        _exit(127);
    }
    free(envp);
    fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);
    *pid_out = pid;
    return master;
}

int hc_pty_resize(int fd, int cols, int rows) {
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    return ioctl(fd, TIOCSWINSZ, &ws);
}
