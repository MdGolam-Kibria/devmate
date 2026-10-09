package dev.devmate.files.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class FilesPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void refusesToStartWithoutAllowedRootsWhenClientRootsAreOff() {
        runner.withPropertyValues("devmate.filesystem.allowed-roots=", "devmate.filesystem.allow-client-roots=false")
                .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("set devmate.filesystem.allowed-roots");
        });
    }

    @Test
    void startsWithoutAllowedRootsWhenClientRootsAreOn() {
        runner.withPropertyValues("devmate.filesystem.allow-client-roots=true").run(context -> {
            var props = context.getBean(FilesProperties.class);
            assertThat(props.allowedRoots()).isEmpty();
            assertThat(props.allowClientRoots()).isTrue();
        });
    }

    @Test
    void bindsCommaSeparatedRootsAndSecureDefaults() {
        runner.withPropertyValues("devmate.filesystem.allowed-roots=/a,/b").run(context -> {
            var props = context.getBean(FilesProperties.class);
            assertThat(props.allowedRoots()).containsExactly(Path.of("/a"), Path.of("/b"));
            assertThat(props.maxFileSize()).isEqualTo(DataSize.ofMegabytes(1));
            assertThat(props.excludedDirectories()).contains(".git", "target", "node_modules");
            assertThat(props.deniedFiles()).contains(".env", "*.pem", "id_rsa*");
            assertThat(props.maxRangeFileSize()).isEqualTo(DataSize.ofMegabytes(64));
            assertThat(props.maxContextLines()).isEqualTo(5);
            assertThat(props.maxTreeDepth()).isEqualTo(8);
            assertThat(props.maxTreeEntries()).isEqualTo(2000);
        });
    }

    @Test
    void rejectsRangeLimitBelowWholeFileLimit() {
        runner.withPropertyValues("devmate.filesystem.allowed-roots=/a", "devmate.filesystem.max-file-size=2MB",
                        "devmate.filesystem.max-range-file-size=1MB")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("max-range-file-size"));
    }

    @Test
    void rejectsNonPositiveLimits() {
        runner.withPropertyValues("devmate.filesystem.allowed-roots=/a", "devmate.filesystem.max-search-results=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FilesProperties.class)
    static class Config {}
}
