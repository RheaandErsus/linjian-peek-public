import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.tools.*;

public final class CompileOnly {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ENGLISH, StandardCharsets.UTF_8);
        files.setLocation(StandardLocation.CLASS_PATH, List.of(Path.of(args[1]).toFile()));
        Files.createDirectories(Path.of(args[2]));
        files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(Path.of(args[2]).toFile()));
        List<Path> sources = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) sources.add(Path.of(line.replace("\"", "")));
        }
        boolean ok = compiler.getTask(null, files, null,
                List.of("-source", "11", "-target", "11", "-encoding", "UTF-8"), null,
                files.getJavaFileObjectsFromPaths(sources)).call();
        // A one-shot process: the OS releases SDK handles at exit. Avoid the sandbox's ZipFS close bug.
        files.flush();
        System.out.println(ok ? "PASS: JDK compiler API compiled " + sources.size() + " Android Java sources" : "FAIL: Android Java compilation");
        System.exit(ok ? 0 : 1);
    }
}
