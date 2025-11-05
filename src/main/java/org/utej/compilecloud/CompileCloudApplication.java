package org.utej.compilecloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CompileCloudApplication {
    static {
        // Prefer modern Windows ConPTY over winpty (less Control-C weirdness)
        System.setProperty("pty4j.preferConpty", "true");
        // Optional: be stricter about console closing behavior
        System.setProperty("winp.useLegacyConPty", "false");
    }

    public static void main(String[] args) {
        SpringApplication.run(CompileCloudApplication.class, args);
    }

}
