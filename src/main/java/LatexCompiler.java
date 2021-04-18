import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class LatexCompiler implements AutoCloseable {
    public final String OUTPUTDIR_PARAMETER = "-output-directory";
    public final String JOBNAME_PARAMETER = "-jobname";
    private final Integer MAX_RETRY = 2;

    private final Map<String, String> arguments = new HashMap();

    private Integer retryCount = 0;

    private final Path tempPath;
    private final File texFile;
    private final File sourceDirectory;

    private CompletableFuture<Boolean> isCompiled;

    /**
     * Creates a new instance of a LatexCompiler
     *
     * @param sourceDirectory Root directory to extract TeX dependent files from (e.g. images)
     * @param template Consumer that uses a Writer to provide the TeX source
     * @throws IOException
     */
    public LatexCompiler(File sourceDirectory, Consumer<Writer> template) throws IOException {
        this.tempPath = Files.createTempDirectory("LatexCompiler");
        this.arguments.put(OUTPUTDIR_PARAMETER, tempPath.toString());

        this.texFile = Paths.get(this.tempPath.toString(), "source.tex").toFile();
        this.sourceDirectory = sourceDirectory == null ? this.texFile.getParentFile() : sourceDirectory;

        FileWriter writer = new FileWriter(this.texFile);
        template.accept(writer);
        writer.close();
    }

    /**
     * Creates a new instance of a LatexCompiler
     *
     * @param texFile TeX source file
     * @throws IOException
     */
    public LatexCompiler(File texFile) throws IOException {
        this.tempPath = Files.createTempDirectory("LatexCompiler");
        this.arguments.put(OUTPUTDIR_PARAMETER, tempPath.toString());

        this.texFile = texFile;
        this.sourceDirectory = texFile.getParentFile();
    }

    /**
     * Deletes compilation files and directories
     */
    @Override
    public void close() {
        var compiledFiles = tempPath.toFile().listFiles();
        for (File f: compiledFiles) f.delete();
        tempPath.toFile().delete();
    }

    /**
     * Adds an argument to the compilation process
     *
     * @param name Argument name, using pdflatex CLI nomenclature (e.g. "-output-directory")
     * @param value Argument value
     * @return The current LatexCompiler instance
     */
    public LatexCompiler addArgument(String name, String value){
        this.isCompiled = null;
        this.arguments.put(name, value);
        return this;
    }

    /**
     * Removes an argument from the compilation process
     *
     * @param name Argument name, using pdflatex CLI nomenclature (e.g. "-output-directory")
     * @return The current LatexCompiler instance
     */
    public LatexCompiler removeArgument(String name) {
        this.isCompiled = null;
        this.arguments.remove(name);
        return this;
    }

    /**
     * Helper method to convert the arguments map into the bash command
     *
     * @return String array representing the bash command
     */
    private String[] getCommandParameters() {
        var command = new String[this.arguments.size() + 1];
        command[0] = "pdflatex";

        int i = 1;
        for(Entry entry : this.arguments.entrySet()){
            command[i++] = entry.getKey() + (entry.getValue() == null ? "" : "=" + entry.getValue());
        }

        return command;
    }

    /**
     * Compiles the TeX source using the provided arguments and files
     *
     * @return CompletableFuture with a boolean indication if the compilation process finished successfully or not
     * @throws IOException
     */
    private CompletableFuture<Boolean> compile() throws IOException {
        var builder = new ProcessBuilder();
        builder.command(getCommandParameters());
        builder.redirectInput(this.texFile);
        builder.directory(this.sourceDirectory);

        return builder.start().onExit().thenCompose((process) -> {
            // Process terminated correctly
            if (process.exitValue() != 0) {
                return CompletableFuture.completedFuture(false);
            }

            try {
                // Requires rerun?
                if(new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .lines().anyMatch((s) -> s.contains("Rerun to get cross-references right."))) {
                    // Recompile
                    return this.retryCount++ < this.MAX_RETRY ? this.compile() : CompletableFuture.completedFuture(false);
                }
            } catch (IOException e) {
                e.printStackTrace();
                return CompletableFuture.completedFuture(false);
            }

            return CompletableFuture.completedFuture(true);
        });
    }

    /**
     * Retrieves a file from the compilation results, by issuing the compilation process if needed
     *
     * @param fileExtension File extension to be retrieved
     * @return CompletableFuture with the requested File or null if the compilation failed
     * @throws IOException
     */
    public CompletableFuture<File> getFile(FileExtensions fileExtension) throws IOException {
        this.isCompiled = this.isCompiled != null ? this.isCompiled : this.compile();

        var compiledFile = Paths.get(tempPath.toString(),
                String.format("%s.%s",
                        this.arguments.computeIfAbsent(JOBNAME_PARAMETER, (s) -> "texput"),
                        fileExtension.toString().toLowerCase()))
                .toFile();

        return this.isCompiled.thenApply((isCompiled) -> isCompiled ? compiledFile : null);
    }

    /**
     * Retrieves the PDF file from the compilation results
     *
     * @return CompletableFuture with the PDF File or null if the compilation failed
     * @throws IOException
     */
    public CompletableFuture<File> getPDF() throws IOException {
        return getFile(FileExtensions.PDF);
    }

    enum FileExtensions {
        PDF, AUX, LOG, TOC
    }
}
