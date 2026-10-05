package dev.devmate.files;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FilesMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(FilesMcpApplication.class, args);
    }
}
