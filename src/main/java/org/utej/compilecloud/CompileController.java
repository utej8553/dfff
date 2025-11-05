package org.utej.compilecloud;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/files")
@CrossOrigin("*")
public class CompileController {

    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.dir"), "temp_files").toAbsolutePath();

    public CompileController() {
        try { Files.createDirectories(BASE_DIR); } catch (IOException ignored) {}
    }

    @PostMapping("/create")
    public Map<String, String> createFile() throws IOException {
        String fileName = UUID.randomUUID().toString() + ".c";
        Files.createFile(BASE_DIR.resolve(fileName));
        return Map.of("fileName", fileName);
    }

    @PostMapping("/save")
    public Map<String, String> save(@RequestBody Map<String, String> body) throws IOException {
        String fileName = body.get("fileName");
        String code = body.get("code");
        if (fileName == null || code == null) return Map.of("status","error","msg","Missing fileName or code");
        Path filePath = BASE_DIR.resolve(fileName);
        if (!Files.exists(filePath)) return Map.of("status","error","msg","File not found");
        Files.writeString(filePath, code);
        return Map.of("status","ok");
    }

    @PostMapping("/compile")
    public Map<String, String> compile(@RequestBody Map<String, String> body)
            throws IOException, InterruptedException {

        String fileName = body.get("fileName");
        if (fileName == null) return Map.of("status","error","msg","Missing fileName");

        Path src = BASE_DIR.resolve(fileName);
        if (!Files.exists(src)) return Map.of("status","error","msg","Source not found");

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String exeBase = fileName.replace(".c", "");
        String exeFile = isWindows ? exeBase + ".exe" : exeBase;
        Path exe = BASE_DIR.resolve(exeFile);

        ProcessBuilder pb = new ProcessBuilder("gcc", src.toString(), "-O2", "-o", exe.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String compilerOutput = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();

        System.out.println("[compile] src=" + src + " exe=" + exe + " exit=" + exit);

        if (exit != 0) return Map.of("status","compile_error","output", compilerOutput);
        return Map.of("status","ok","exe", exe.getFileName().toString(), "output", compilerOutput);
    }
}
