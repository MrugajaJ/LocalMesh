package dev.localmesh.cli

import dev.localmesh.cli.cmd.ConnectCommand
import dev.localmesh.cli.cmd.InterceptCommand
import picocli.CommandLine
import spock.lang.Specification

import java.io.PrintWriter
import java.io.StringWriter

class CliArgParsingSpec extends Specification {

    def "localmesh intercept with no args exits code 2 and prints usage"() {
        given:
        def cli = new LocalMeshCli()
        def cmd = new CommandLine(cli)
        def sw = new StringWriter()
        cmd.setErr(new PrintWriter(sw))

        when:
        int exitCode = cmd.execute("intercept")

        then:
        exitCode == 2
        sw.toString().contains("Missing required parameter: '<serviceName>'")
    }

    def "localmesh intercept payment-service with no --port exits code 2"() {
        given:
        def cli = new LocalMeshCli()
        def cmd = new CommandLine(cli)
        def sw = new StringWriter()
        cmd.setErr(new PrintWriter(sw))

        when:
        int exitCode = cmd.execute("intercept", "payment-service")

        then:
        exitCode == 2
        sw.toString().contains("Missing required option: '--port=<localPort>'")
    }

    def "localmesh --verbose intercept payment-service --port 8080 sets verbose correctly"() {
        given:
        def cli = new LocalMeshCli()
        def cmd = new CommandLine(cli)

        when:
        // Use parseArgs to avoid executing the actual command logic which blocks on Thread.join()
        def parseResult = cmd.parseArgs("--verbose", "intercept", "payment-service", "--port", "8080")

        then:
        cli.verbose == true
        parseResult.subcommand().commandSpec().userObject() instanceof InterceptCommand
        (parseResult.subcommand().commandSpec().userObject() as InterceptCommand).localPort == 8080
    }

    def "localmesh --api-url http://custom:9090 connect sets apiUrl correctly"() {
        given:
        def cli = new LocalMeshCli()
        def cmd = new CommandLine(cli)

        when:
        def parseResult = cmd.parseArgs("--api-url", "http://custom:9090", "connect")

        then:
        cli.apiUrl == "http://custom:9090"
        parseResult.subcommand().commandSpec().userObject() instanceof ConnectCommand
    }
}
