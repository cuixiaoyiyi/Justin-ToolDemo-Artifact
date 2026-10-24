package cn.ac.ios.justin.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "justin", mixinStandardHelpOptions = true, version = "Justin 0.9.0",
        description = "Automatic unit test generation for Java.",
        subcommands = {GenerateCommand.class, MeasureCommand.class, ReportCommand.class, DaemonCommand.class})
public final class JustinCli implements Runnable {

    @Override public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int code = new CommandLine(new JustinCli()).execute(args);
        System.exit(code);
    }
}
