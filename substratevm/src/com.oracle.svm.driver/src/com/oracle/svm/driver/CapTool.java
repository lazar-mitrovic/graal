package com.oracle.svm.driver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CapTool { ;
    static Map<String, String> args = new HashMap<>();

    static final String[] classPathOptions = new String[]
            {"-cp", "-classpath", "--class-path"};

    static String classPath;
    static String libc;


    public static String[] parseArgs(String[] args) {

    }

    public static String[] init(String[] args) {
        List<String> niArgs = new ArrayList<String>(Arrays.asList(
                "-H:CAPQueryCodeDir='../capSrc'",
                "-H:+DontRunCAPQueryCode"
        ));
        return niArgs.toArray(new String[niArgs.size()]);
    }

    public static void main(String[] args) {
        String[] NativeImageArgs = init(args);
        NativeImage.main(NativeImageArgs);

    }

    public static class JDK9Plus {
        public static void main(String[] args) {
            String[] NativeImageArgs = init(args);
            NativeImage.JDK9Plus.main(NativeImageArgs);

        }
    }

}