package org.utej.compilecloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CompileCloudApplication {
    static {
        System.setProperty("pty4j.preferConpty", "true");
        System.setProperty("winp.useLegacyConPty", "false");
    }

    public static void main(String[] args) {
        SpringApplication.run(CompileCloudApplication.class, args);
    }

}
