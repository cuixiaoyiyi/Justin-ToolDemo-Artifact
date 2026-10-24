package cn.ac.ios.justin.cli;

import cn.ac.ios.justin.api.GenerationResult;
import cn.ac.ios.justin.api.HtmlReportWriter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "report", mixinStandardHelpOptions = true, description = "Re-render report.html from an existing run's report.json.")
public final class ReportCommand implements Callable<Integer> {

    @Option(names = "--run", required = true, description = "Run directory (contains reports/report.json) or the report.json file itself")
    Path run;

    @Option(names = "--out", description = "Output HTML file (default: reports/report.html next to the JSON)")
    Path out;

    @Override public Integer call() throws Exception {
        Path json = Files.isDirectory(run) ? run.resolve("reports").resolve("report.json") : run;
        if (!Files.exists(json)) { System.err.println("not found: " + json); return 1; }
        ObjectMapper om = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        GenerationResult r = om.readValue(json.toFile(), GenerationResult.class);
        Path html = out != null ? out : json.resolveSibling("report.html");
        HtmlReportWriter.write(r, html);
        System.out.println("Report:    " + html);
        return 0;
    }
}
