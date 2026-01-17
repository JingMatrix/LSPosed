package org.lsposed.lspd.cli;

import picocli.CommandLine;

public class GlobalOptions {
    @CommandLine.Option(names = {"-j", "--json"}, description = "Output results in JSON format.")
    public boolean jsonOutput;
}
