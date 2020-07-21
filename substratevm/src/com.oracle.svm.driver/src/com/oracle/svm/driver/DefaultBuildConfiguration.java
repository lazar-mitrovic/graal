package com.oracle.svm.driver;

import com.oracle.svm.core.OS;
import org.graalvm.nativeimage.ProcessProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

class DefaultBuildConfiguration implements BuildConfiguration {
    private final Path workDir;
    private final Path rootDir;
    private final List<String> args;

    DefaultBuildConfiguration(List<String> args) {
        this(null, null, args);
    }

    @SuppressWarnings("deprecation")
    DefaultBuildConfiguration(Path rootDir, Path workDir, List<String> args) {
        this.args = args;
        this.workDir = workDir != null ? workDir : Paths.get(".").toAbsolutePath().normalize();
        if (rootDir != null) {
            this.rootDir = rootDir;
        } else {
            if (NativeImage.IS_AOT) {
                Path executablePath = Paths.get(ProcessProperties.getExecutableName());
                assert executablePath != null;
                Path binDir = executablePath.getParent();
                Path rootDirCandidate = binDir.getParent();
                if (rootDirCandidate.endsWith(NativeImage.platform)) {
                    rootDirCandidate = rootDirCandidate.getParent();
                }
                if (rootDirCandidate.endsWith(Paths.get("lib", "svm"))) {
                    rootDirCandidate = rootDirCandidate.getParent().getParent();
                }
                this.rootDir = rootDirCandidate;
            } else {
                String rootDirProperty = "native-image.root";
                String rootDirString = System.getProperty(rootDirProperty);
                if (rootDirString == null) {
                    rootDirString = System.getProperty("java.home");
                }
                this.rootDir = Paths.get(rootDirString);
            }
        }
    }

    @Override
    public Path getWorkingDirectory() {
        return workDir;
    }

    @Override
    public Path getJavaHome() {
        return useJavaModules() ? rootDir : rootDir.getParent();
    }

    @Override
    public Path getJavaExecutable() {
        Path binJava = Paths.get("bin", OS.getCurrent() == OS.WINDOWS ? "java.exe" : "java");
        if (Files.isExecutable(rootDir.resolve(binJava))) {
            return rootDir.resolve(binJava);
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null) {
            throw NativeImage.showError("Environment variable JAVA_HOME is not set");
        }
        Path javaHomeDir = Paths.get(javaHome);
        if (!Files.isDirectory(javaHomeDir)) {
            throw NativeImage.showError("Environment variable JAVA_HOME does not refer to a directory");
        }
        if (!Files.isExecutable(javaHomeDir.resolve(binJava))) {
            throw NativeImage.showError("Environment variable JAVA_HOME does not refer to a directory with a " + binJava + " executable");
        }
        return javaHomeDir.resolve(binJava);
    }

    @Override
    public List<Path> getBuilderClasspath() {
        List<Path> result = new ArrayList<>();
        if (useJavaModules()) {
            result.addAll(NativeImage.getJars(rootDir.resolve(Paths.get("lib", "jvmci")), "graal-sdk", "graal", "enterprise-graal"));
        }
        result.addAll(NativeImage.getJars(rootDir.resolve(Paths.get("lib", "svm", "builder"))));
        return result;
    }

    @Override
    public List<Path> getBuilderCLibrariesPaths() {
        return Collections.singletonList(rootDir.resolve(Paths.get("lib", "svm", "clibraries")));
    }

    @Override
    public List<Path> getImageProvidedClasspath() {
        return NativeImage.getJars(rootDir.resolve(Paths.get("lib", "svm")));
    }

    @Override
    public Path getBuilderInspectServerPath() {
        Path inspectPath = rootDir.resolve(Paths.get("lib", "svm", "inspect"));
        if (Files.isDirectory(inspectPath)) {
            return inspectPath;
        }
        return null;
    }

    @Override
    public List<Path> getBuilderJVMCIClasspath() {
        return NativeImage.getJars(rootDir.resolve(Paths.get("lib", "jvmci")));
    }

    @Override
    public List<Path> getBuilderJVMCIClasspathAppend() {
        return getBuilderJVMCIClasspath().stream()
                        .filter(f -> f.getFileName().toString().toLowerCase().endsWith("graal.jar"))
                        .collect(Collectors.toList());
    }

    @Override
    public List<Path> getBuilderBootClasspath() {
        return NativeImage.getJars(rootDir.resolve(Paths.get("lib", "boot")));
    }

    @Override
    public List<Path> getBuilderModulePath() {
        List<Path> result = new ArrayList<>();
        result.addAll(NativeImage.getJars(rootDir.resolve(Paths.get("lib", "jvmci")), "graal-sdk", "enterprise-graal"));
        result.addAll(NativeImage.getJars(rootDir.resolve(Paths.get("lib", "truffle")), "truffle-api"));
        return result;
    }

    @Override
    public List<Path> getBuilderUpgradeModulePath() {
        return NativeImage.getJars(rootDir.resolve(Paths.get("lib", "jvmci")), "graal", "graal-management");
    }

    @Override
    public List<Path> getImageClasspath() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getBuildArgs() {
        if (args.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> buildArgs = new ArrayList<>();
        buildArgs.addAll(Arrays.asList("--configurations-path", rootDir.toString()));
        buildArgs.addAll(Arrays.asList("--configurations-path", rootDir.resolve(Paths.get("lib", "svm")).toString()));
        buildArgs.addAll(args);
        return buildArgs;
    }

    @Override
    public Path getAgentJAR() {
        return rootDir.resolve(Paths.get("lib", "svm", "builder", "svm.jar"));
    }

    @Override
    public Optional<Path> getResourcesJar() {
        return Optional.of(rootDir.resolve(Paths.get("lib", "resources.jar")));
    }
}
