package com.oracle.svm.driver;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.util.VMError;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public interface BuildConfiguration {

    static final String DEFAULT_GENERATOR_CLASS_NAME = "com.oracle.svm.hosted.NativeImageGeneratorRunner";
    static final String DEFAULT_GENERATOR_9PLUS_SUFFIX = "$JDK9Plus";

    /**
     * @return the name of the image generator main class.
     */
    default String getGeneratorMainClass() {
        String generatorClassName = DEFAULT_GENERATOR_CLASS_NAME;
        if (useJavaModules()) {
            generatorClassName += DEFAULT_GENERATOR_9PLUS_SUFFIX;
        }
        return generatorClassName;
    }

    /**
     * @return relative path usage get resolved against this path (also default path for image
     *         building)
     */
    Path getWorkingDirectory();

    /**
     * @return java.home that is associated with this BuildConfiguration
     */
    default Path getJavaHome() {
        throw VMError.unimplemented();
    }

    /**
     * @return path to Java executable
     */
    default Path getJavaExecutable() {
        throw VMError.unimplemented();
    }

    /**
     * @return true if Java modules system should be used
     */
    default boolean useJavaModules() {
        try {
            Class.forName("java.lang.Module");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return true;
    }

    /**
     * @return classpath for SubstrateVM image builder components
     */
    default List<Path> getBuilderClasspath() {
        throw VMError.unimplemented();
    }

    /**
     * @return base clibrary paths needed for general image building
     */
    default List<Path> getBuilderCLibrariesPaths() {
        throw VMError.unimplemented();
    }

    /**
     * @return path to content of the inspect web server (points-to analysis debugging)
     */
    default Path getBuilderInspectServerPath() {
        throw VMError.unimplemented();
    }

    /**
     * @return base image classpath needed for every image (e.g. LIBRARY_SUPPORT)
     */
    default List<Path> getImageProvidedClasspath() {
        throw VMError.unimplemented();
    }

    /**
     * @return JVMCI API classpath for image builder (jvmci + graal jars)
     */
    default List<Path> getBuilderJVMCIClasspath() {
        throw VMError.unimplemented();
    }

    /**
     * @return entries for jvmci.class.path.append system property (if needed)
     */
    default List<Path> getBuilderJVMCIClasspathAppend() {
        throw VMError.unimplemented();
    }

    /**
     * @return boot-classpath for image builder (graal-sdk.jar)
     */
    default List<Path> getBuilderBootClasspath() {
        throw VMError.unimplemented();
    }

    /**
     * @return additional arguments for JVM that runs image builder
     */
    default List<String> getBuilderJavaArgs() {
        String javaVersion = String.valueOf(JavaVersionUtil.JAVA_SPEC);
        String[] flagsForVersion = NativeImage.graalCompilerFlags.get(javaVersion);
        if (flagsForVersion == null) {
            NativeImage.showError(String.format("Image building not supported for Java version %s in %s with VM configuration \"%s\"",
                            System.getProperty("java.version"),
                            System.getProperty("java.home"),
                            System.getProperty("java.vm.name")));
        }

        if (NativeImage.useJVMCINativeLibrary == null) {
            NativeImage.useJVMCINativeLibrary = false;
            ProcessBuilder pb = new ProcessBuilder();
            List<String> command = pb.command();
            command.add(getJavaExecutable().toString());
            command.add("-XX:+PrintFlagsFinal");
            command.add("-version");
            try {
                Process process = pb.start();
                try (java.util.Scanner inputScanner = new java.util.Scanner(process.getInputStream())) {
                    while (inputScanner.hasNextLine()) {
                        String line = inputScanner.nextLine();
                        if (line.contains("bool UseJVMCINativeLibrary")) {
                            String value = SubstrateUtil.split(line, "=")[1];
                            if (value.trim().startsWith("true")) {
                                NativeImage.useJVMCINativeLibrary = true;
                                break;
                            }
                        }
                    }
                }
                process.waitFor();
                process.destroy();
            } catch (Exception e) {
                /* Probing fails silently */
            }
        }

        ArrayList<String> builderJavaArgs = new ArrayList<>();
        builderJavaArgs.addAll(Arrays.asList(flagsForVersion));
        if (NativeImage.useJVMCINativeLibrary) {
            builderJavaArgs.add("-XX:+UseJVMCINativeLibrary");
        } else {
            builderJavaArgs.add("-XX:-UseJVMCICompiler");
        }
        return builderJavaArgs;
    }

    /**
     * @return entries for the --module-path of the image builder
     */
    default List<Path> getBuilderModulePath() {
        throw VMError.unimplemented();
    }

    /**
     * @return entries for the --upgrade-module-path of the image builder
     */
    default List<Path> getBuilderUpgradeModulePath() {
        throw VMError.unimplemented();
    }

    /**
     * @return classpath for image (the classes the user wants to build an image from)
     */
    default List<Path> getImageClasspath() {
        throw VMError.unimplemented();
    }

    /**
     * @return native-image (i.e. image build) arguments
     */
    default List<String> getBuildArgs() {
        throw VMError.unimplemented();
    }

    /**
     * @return true for fallback image building
     */
    default boolean buildFallbackImage() {
        return false;
    }

    default Path getAgentJAR() {
        return null;
    }

    /**
     * ResourcesJar packs resources files needed for some jdk services such as xml
     * serialization.
     *
     * @return the path to the resources.jar file
     */
    default Optional<Path> getResourcesJar() {
        return Optional.empty();
    }
}
