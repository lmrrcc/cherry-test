package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.core.PackageRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeDiscoverTest {
    @Test
    void discovers_vitest_tests_from_source(@TempDir Path root) throws IOException {
        Path source = Files.createDirectories(root.resolve("mini/mini-ts-web-react/src"));
        Files.writeString(
                source.resolve("display.test.tsx"),
                "\ndescribe(\"App\", () => {\n  it(\"renders formatted USDT from API response\", async () => {});\n});\n",
                StandardCharsets.UTF_8);
        WorkspaceMember member = new WorkspaceMember(
                "mini-ts-web-react", Path.of("mini/mini-ts-web-react/package.json"), "mini", PackageRuntime.NODE);
        List<DiscoveredTest> tests = NodeDiscover.discoverPackageNodeTests(root, member, "mini");
        assertThat(tests).hasSize(1);
        assertThat(tests.getFirst().suite()).isEqualTo("unit");
        assertThat(tests.getFirst().function()).contains("renders_formatted_usdt");
    }

    @Test
    void slugify_normalizes_titles() {
        assertThat(VitestReport.slugify("formats USDT value")).isEqualTo("formats_usdt_value");
    }
}
