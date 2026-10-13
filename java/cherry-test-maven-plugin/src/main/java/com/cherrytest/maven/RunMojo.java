package com.cherrytest.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo(name = "run", aggregator = true, requiresProject = false, threadSafe = true)
public final class RunMojo extends AbstractMojo {
    @Parameter(property = "cherry-test.args", defaultValue = "")
    private String arguments = "";

    @Parameter(defaultValue = "${session.executionRootDirectory}", readonly = true, required = true)
    private File executionRoot = new File(".");

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts = List.of();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        List<Path> classpath = pluginArtifacts.stream()
                .map(Artifact::getFile)
                .map(File::toPath)
                .toList();
        List<String> command =
                CliCommand.of(Path.of(System.getProperty("java.home"), "bin", "java"), classpath, arguments);
        int status;
        try {
            status = new ProcessBuilder(command)
                    .directory(executionRoot)
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (IOException failure) {
            throw new MojoExecutionException("failed to start cherry-test: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("interrupted while cherry-test was running", interrupted);
        }
        if (status != 0) {
            throw new MojoFailureException("cherry-test exited with status " + status);
        }
    }
}
