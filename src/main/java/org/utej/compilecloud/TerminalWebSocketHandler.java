package org.utej.compilecloud;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.socket.BinaryMessage;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.dir"), "temp_files").toAbsolutePath();
    private static final int BUF_SIZE = 8192;

    private static class SessionState {
        PtyProcess pty;
        OutputStream stdin;
        Future<?> pumpTask;
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicBoolean sawCtrlC = new AtomicBoolean(false);
        final AtomicBoolean windowsShell = new AtomicBoolean(false);
        final AtomicBoolean rcSeen = new AtomicBoolean(false);
        volatile Integer reportedExitCode = null;
    }

    private final ConcurrentHashMap<String, SessionState> states = new ConcurrentHashMap<>();
    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    static {
        // Prefer ConPTY when available (more stable than winpty)
        System.setProperty("pty4j.preferConpty", "true");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Files.createDirectories(BASE_DIR);

        URI uri = session.getUri();
        String fileName = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("fileName");

        if (fileName == null || !fileName.endsWith(".c")) {
            session.sendMessage(new TextMessage("ERROR: missing/invalid fileName\n"));
            session.close();
            return;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String exeBase = fileName.substring(0, fileName.length() - 2);
        Path exeNoExt = BASE_DIR.resolve(exeBase);
        Path exeWin = BASE_DIR.resolve(exeBase + ".exe");
        Path finalExe = Files.exists(exeNoExt) ? exeNoExt : (Files.exists(exeWin) ? exeWin : null);

        if (finalExe == null) {
            session.sendMessage(new TextMessage("ERROR: executable not found. Compile first.\n"));
            session.close();
            return;
        }

        // --- Launch strategy ---
        // Windows: start a *persistent* cmd and inject the command to run the exe, then echo __RC:exit__
        // Unix: run binary directly.
        String[] command = isWindows
                ? new String[] { "cmd.exe", "/Q", "/D" }   // quiet, no AutoRun
                : new String[] { finalExe.toString() };

        PtyProcessBuilder builder = new PtyProcessBuilder(command)
                .setEnvironment(new HashMap<>(System.getenv()))
                .setDirectory(BASE_DIR.toString())
                .setInitialColumns(80)
                .setInitialRows(24);

        PtyProcess pty = builder.start();

        SessionState st = new SessionState();
        st.pty = pty;
        st.stdin = pty.getOutputStream();
        st.windowsShell.set(isWindows);

        // Pump PTY -> WS (binary). Also sniff for "__RC:<n>__" on Windows to capture the true exit code.
        Pattern rcPattern = Pattern.compile("__RC:(-?\\d+)__");
        st.pumpTask = ioPool.submit(() -> {
            try (InputStream is = pty.getInputStream()) {
                byte[] buf = new byte[BUF_SIZE];
                int n;
                ByteArrayOutputStream sniff = new ByteArrayOutputStream(4096);

                while ((n = is.read(buf)) != -1 && session.isOpen()) {
                    session.sendMessage(new BinaryMessage(Arrays.copyOf(buf, n)));

                    // On Windows, sniff text to pick out __RC:...__
                    if (st.windowsShell.get() && !st.rcSeen.get()) {
                        sniff.write(buf, 0, n);
                        // Limit sniff buffer size
                        if (sniff.size() > 64_000) {
                            sniff.reset(); // just keep it bounded
                        }
                        String text = sniff.toString(StandardCharsets.UTF_8);
                        Matcher m = rcPattern.matcher(text);
                        if (m.find()) {
                            try {
                                st.reportedExitCode = Integer.parseInt(m.group(1));
                            } catch (NumberFormatException ignored) {}
                            st.rcSeen.set(true);
                        }
                    }
                }
            } catch (IOException ignored) { }
        });

        states.put(session.getId(), st);

        session.sendMessage(new TextMessage("[started] " + finalExe.getFileName() + "\n"));

        // If Windows: inject the command to run exe and echo RC, then return to shell prompt (keeps terminal open)
        if (isWindows) {
            // Quote path in case of spaces; run, then echo RC marker
            String cmd = "\"" + finalExe.getFileName().toString() + "\" & echo __RC:%errorlevel%__\r\n";
            st.stdin.write(cmd.getBytes(StandardCharsets.UTF_8));
            st.stdin.flush();
        }

        // Waiter to report exit (with small delay to flush)
        ioPool.submit(() -> {
            try {
                int raw = pty.waitFor();
                st.alive.set(false);
                try { Thread.sleep(30); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                if (!session.isOpen()) return;

                Integer exitToShow = st.reportedExitCode;
                if (!isWindows) {
                    // Unix: the process *is* the exe, so raw is fine
                    exitToShow = (exitToShow == null) ? raw : exitToShow;
                } else {
                    // Windows shell: prefer parsed __RC:...__, fallback to raw if not seen
                    if (exitToShow == null) exitToShow = raw;

                    // If we *didn’t* see Ctrl+C from client, treat 0xC000013A as a spurious control event and show 0
                    if (!st.sawCtrlC.get() && (exitToShow == -1073741510 || exitToShow == 0xC000013A)) {
                        exitToShow = 0;
                    }
                }

                session.sendMessage(new TextMessage("\r\n[process exited with code " + exitToShow + "]\r\n"));
                session.sendMessage(new TextMessage("[terminal idle – press Run to start again]\r\n"));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) { }
        });
    }

    /**
     * Protocol:
     * - Normal keystrokes are text frames.
     * - Ctrl+C is char 0x03.
     * - Resize: "__RESIZE__:<cols>x<rows>"
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SessionState st = states.get(session.getId());
        if (st == null) return;

        String data = message.getPayload();

        if (data.startsWith("__RESIZE__:")) {
            if (!st.alive.get()) return; // ignore resize after exit
            String[] parts = data.substring("__RESIZE__:".length()).trim().split("x");
            if (parts.length == 2) {
                try {
                    int cols = Integer.parseInt(parts[0]);
                    int rows = Integer.parseInt(parts[1]);
                    try {
                        st.pty.setWinSize(new WinSize(rows, cols)); // rows, cols
                    } catch (Throwable t) {
                        // PTY may already be closed; ignore
                    }
                } catch (NumberFormatException ignored) { }
            }
            return;
        }

        // Ctrl+C (0x03)
        if (data.length() == 1 && data.charAt(0) == 3) {
            st.sawCtrlC.set(true);
            try { st.pty.destroy(); } catch (Exception ignored) {}
            st.alive.set(false);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("\r\n^C\r\n[process terminated]\r\n"));
            }
            return;
        }

        // Forward user input
        if (st.alive.get()) {
            try {
                st.stdin.write(data.getBytes(StandardCharsets.UTF_8));
                st.stdin.flush();
            } catch (IOException ignored) {
                st.alive.set(false);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        SessionState st = states.remove(session.getId());
        if (st != null) {
            try { st.stdin.close(); } catch (Exception ignored) {}
            try { st.pty.destroy(); } catch (Exception ignored) {}
            if (st.pumpTask != null) st.pumpTask.cancel(true);
            st.alive.set(false);
        }
    }
}
