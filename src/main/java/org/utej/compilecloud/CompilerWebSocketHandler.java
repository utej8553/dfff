package org.utej.compilecloud;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;


@Component
public class CompilerWebSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final long COMPILE_TIMEOUT_SECONDS = 5;
    private static final long EXECUTION_TIMEOUT_SECONDS = 10;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        if (payload.startsWith("RUN:")) {
            String code = payload.substring(4);
            executorService.submit(() -> executeCode(session, code));
        } else if (payload.startsWith("INPUT:")) {
            String input = payload.substring(6);
            // Add newline for console input processing by C/C++ runtime
            sendInputToProcess(session, input + "\n");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        stopProcess(session);
    }
    private void executeCode(WebSocketSession session, String code) {
        Process runProcess = null;
        File sourceFile = null;
        String sessionId = session.getId();
        Future<?> outputFuture = null;
        Future<?> errorFuture = null;

        try {
            sourceFile = new File(sessionId + ".c");
            File outputFile = new File(sessionId + ".out");
            try (PrintWriter writer = new PrintWriter(sourceFile)) { writer.print(code); }

            // 2. Compile
            synchronized (session) { session.sendMessage(new TextMessage("BUILD_LOG: Compiling...\n")); }
            ProcessBuilder compileBuilder = new ProcessBuilder("gcc", sourceFile.getName(), "-o", outputFile.getName());
            compileBuilder.directory(sourceFile.getParentFile());
            Process compileProcess = compileBuilder.start();

            boolean compiledWithinTime = compileProcess.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!compiledWithinTime) {
                compileProcess.destroyForcibly();
                synchronized (session) {
                    session.sendMessage(new TextMessage("ERROR: Compilation timed out."));
                }
                return;
            }

            int exitCode = compileProcess.exitValue();
            String compileError = readStream(compileProcess.getErrorStream());

            if (exitCode != 0) {
                synchronized (session) {
                    session.sendMessage(new TextMessage("ERROR: Compilation Failed (Exit Code: " + exitCode + ")\n" + compileError));
                }
                return;
            }

            // 3. Execute
            synchronized (session) {
                session.sendMessage(new TextMessage("BUILD_LOG: Compilation successful. Running...\n"));
            }
            ProcessBuilder runBuilder = new ProcessBuilder("./" + outputFile.getName());
            runBuilder.directory(sourceFile.getParentFile());
            runProcess = runBuilder.start();

            runningProcesses.put(sessionId, runProcess);

            // 4. Stream real-time output
            StreamGobbler outputGobbler = new StreamGobbler(runProcess.getInputStream(), session, "OUTPUT:");
            StreamGobbler errorGobbler = new StreamGobbler(runProcess.getErrorStream(), session, "OUTPUT: [Error] ");

            outputFuture = executorService.submit(outputGobbler);
            errorFuture = executorService.submit(errorGobbler);

            // 5. Enforce a timeout
            if (!runProcess.waitFor(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runProcess.destroyForcibly();
                outputFuture.cancel(true);
                errorFuture.cancel(true);

                synchronized (session) {
                    session.sendMessage(new TextMessage("OUTPUT: \r\n\u001b[31;1m*** PROCESS KILLED (Timeout) ***\u001b[0m\r\n"));
                    session.sendMessage(new TextMessage("END:TIMEOUT"));
                }
            } else {
                outputFuture.get();
                errorFuture.get();

                synchronized (session) {
                    session.sendMessage(new TextMessage("OUTPUT:\r\nProcess exited with status: " + runProcess.exitValue() + "\r\n"));
                    session.sendMessage(new TextMessage("END:SUCCESS"));
                }
            }

        } catch (Exception e) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage("ERROR: An internal server error occurred: " + e.getMessage()));
                }
            } catch (IOException ioException) { /* ignored */ }
        } finally {
            stopProcess(session);
            if (sourceFile != null) {
                new File(sessionId + ".out").delete();
                sourceFile.delete();
            }
        }
    }

    // --- Utility Methods ---
    private void sendInputToProcess(WebSocketSession session, String input) throws IOException {
        Process process = runningProcesses.get(session.getId());
        if (process != null && process.isAlive()) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } else {
            synchronized (session) {
                session.sendMessage(new TextMessage("OUTPUT: \r\n\u001b[31;1mError:\u001b[0m No active program running to receive input.\r\n"));
            }
        }
    }

    private void stopProcess(WebSocketSession session) {
        Process process = runningProcesses.remove(session.getId());
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private String readStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) { sb.append(line).append("\n"); }
            return sb.toString();
        }
    }

    private static class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final WebSocketSession session;
        private final String prefix;

        public StreamGobbler(InputStream inputStream, WebSocketSession session, String prefix) {
            this.inputStream = inputStream;
            this.session = session;
            this.prefix = prefix;
        }

        @Override
        public void run() {
            try (InputStreamReader isr = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                int character;
                while ((character = isr.read()) != -1) {
                    if (session.isOpen()) {
                        String output = prefix + (char) character;
                        synchronized (session) {
                            session.sendMessage(new TextMessage(output));
                        }
                    } else { break; }
                }
            } catch (IOException e) { /* ignored */ }
        }
    }
}