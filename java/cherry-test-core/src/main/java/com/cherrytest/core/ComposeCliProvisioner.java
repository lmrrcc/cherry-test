package com.cherrytest.core;

import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class ComposeCliProvisioner implements ScopeProvisioner {
    static final ComposeCliProvisioner INSTANCE = new ComposeCliProvisioner();

    private ComposeCliProvisioner() {}

    private static SystemCommand compose(List<Path> composeFiles) {
        SystemCommand command =
                SystemCommand.of("docker").arg("compose").arg("-p").arg(Workspace.composeProject());
        for (Path file : composeFiles) {
            command.arg("-f").arg(file.toString());
        }
        return command;
    }

    @Override
    public void up(
            Path root,
            TestItConfig config,
            String scope,
            List<String> projectNames,
            List<Path> composeFiles,
            List<String> services) {
        SystemCommand command = compose(composeFiles).args("up", "-d", "--build", "--wait");
        services.forEach(command::arg);
        command.currentDir(root);
        int status;
        try {
            status = command.status();
        } catch (IOException exception) {
            throw AnyhowError.context("docker compose up", new AnyhowError(RustIoError.display(exception)));
        }
        if (status != 0) {
            throw new AnyhowError("docker compose up failed for scope `" + scope + "`");
        }
    }

    @Override
    public String mappedAddress(Path root, List<Path> composeFiles, String project, String service, int port) {
        SystemCommand command = compose(composeFiles)
                .args("port", service, Integer.toString(port))
                .currentDir(root);
        CommandOutput output;
        try {
            output = command.output();
        } catch (IOException exception) {
            throw AnyhowError.context("docker compose port", new AnyhowError(RustIoError.display(exception)));
        }
        if (!output.success()) {
            throw new AnyhowError("docker compose port " + service + " " + port + " failed: " + output.stderrLossy());
        }
        return RustText.trim(output.stdoutUtf8Strict());
    }

    @Override
    public void down(Path root, List<Path> composeFiles) {
        SystemCommand command = compose(composeFiles).args("down", "-v").currentDir(root);
        int status;
        try {
            status = command.status();
        } catch (IOException exception) {
            throw AnyhowError.context("docker compose down", new AnyhowError(RustIoError.display(exception)));
        }
        if (status != 0) {
            throw new AnyhowError("Docker teardown failed");
        }
    }
}
