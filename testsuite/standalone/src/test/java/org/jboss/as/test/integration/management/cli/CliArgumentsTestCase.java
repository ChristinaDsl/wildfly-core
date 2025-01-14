/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.test.integration.management.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;

import org.apache.commons.io.FileUtils;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.core.testrunner.WildFlyRunner;

/**
 *
 * @author Dominik Pospisil <dpospisi@redhat.com>
 * @author Alexey Loubyansky
 */
@RunWith(WildFlyRunner.class)
public class CliArgumentsTestCase {

    private static final String tempDir = TestSuiteEnvironment.getTmpDir();

    @Test
    public void testVersionArgument() throws Exception {
        CliProcessWrapper cli = new CliProcessWrapper()
                .addCliArgument("--version");
        final String result = cli.executeNonInteractive();
        assertNotNull(result);
        assertTrue(result, result.contains("JBOSS_HOME"));
        assertTrue(result, result.contains("Release"));
        assertTrue(result, result.contains("JAVA_HOME"));
        assertTrue(result, result.contains("java.version"));
        assertTrue(result, result.contains("java.vm.vendor"));
        assertTrue(result, result.contains("java.vm.version"));
        assertTrue(result, result.contains("os.name"));
        assertTrue(result, result.contains("os.version"));
    }

    @Test
    public void testVersionAsCommandArgument() throws Exception {
        CliProcessWrapper cli = new CliProcessWrapper()
                .addCliArgument("--command=version");
        final String result = cli.executeNonInteractive();
        assertNotNull(result);
        assertTrue(result, result.contains("JBOSS_HOME"));
        assertTrue(result, result.contains("Release"));
        assertTrue(result, result.contains("JAVA_HOME"));
        assertTrue(result, result.contains("java.version"));
        assertTrue(result, result.contains("java.vm.vendor"));
        assertTrue(result, result.contains("java.vm.version"));
        assertTrue(result, result.contains("os.name"));
        assertTrue(result, result.contains("os.version"));
    }

    @Test
    public void testFileArgument() throws Exception {

        // prepare file
        File cliScriptFile = new File(tempDir, "testScript.cli");
        if (cliScriptFile.exists()) Assert.assertTrue(cliScriptFile.delete());
        FileUtils.writeStringToFile(cliScriptFile, "version" + TestSuiteEnvironment.getSystemProperty("line.separator"));

        // pass it to CLI
        CliProcessWrapper cli = new CliProcessWrapper()
                .addCliArgument("--file=" + cliScriptFile.getAbsolutePath());
        final String result = cli.executeNonInteractive();
        assertNotNull(result);
        assertTrue(result, result.contains("JBOSS_HOME"));
        assertTrue(result, result.contains("Release"));
        assertTrue(result, result.contains("JAVA_HOME"));
        assertTrue(result, result.contains("java.version"));
        assertTrue(result, result.contains("java.vm.vendor"));
        assertTrue(result, result.contains("java.vm.version"));
        assertTrue(result, result.contains("os.name"));
        assertTrue(result, result.contains("os.version"));

        cliScriptFile.delete();
    }

    @Test
    public void testConnectArgument() throws Exception {
        CliProcessWrapper cli = new CliProcessWrapper()
                .addCliArgument("--commands=connect,version,ls")
                .addCliArgument("--controller=" + TestSuiteEnvironment.getServerAddress() + ":" + (TestSuiteEnvironment.getServerPort()));

        final String result = cli.executeNonInteractive();

        assertNotNull(result);
        assertTrue(result, result.contains("JBOSS_HOME"));
        assertTrue(result, result.contains("Release"));
        assertTrue(result, result.contains("JAVA_HOME"));
        assertTrue(result, result.contains("java.version"));
        assertTrue(result, result.contains("java.vm.vendor"));
        assertTrue(result, result.contains("java.vm.version"));
        assertTrue(result, result.contains("os.name"));
        assertTrue(result, result.contains("os.version"));

        assertTrue(result.contains("subsystem"));
        assertTrue(result.contains("extension"));
    }

    @Test
    public void testWrongController() throws Exception {
        CliProcessWrapper cli = new CliProcessWrapper()
                .addCliArgument("--connect")
                .addCliArgument("--controller=" + TestSuiteEnvironment.getServerAddress() + ":" + (TestSuiteEnvironment.getServerPort() - 1))
                .addCliArgument("quit");
        cli.executeNonInteractive();

        int exitCode = cli.getProcessExitValue();
        assertTrue(exitCode != 0);
    }

    @Test
    public void testMisspelledParameter() throws Exception {
        CliProcessWrapper cli = new CliProcessWrapper()
           .addCliArgument("--connect")
           .addCliArgument("--controler=" + TestSuiteEnvironment.getServerAddress() + ":" + (TestSuiteEnvironment.getServerPort() - 1))
           .addCliArgument("quit");
        cli.executeNonInteractive();

        int exitCode = cli.getProcessExitValue();
        String output = cli.getOutput();
        try (BufferedReader reader = new BufferedReader(new StringReader(output))) {
            String line = reader.readLine();
            // Skip lines like: "Picked up _JAVA_OPTIONS: ..."
            while (line.startsWith("Picked up _JAVA_")) {
                line = reader.readLine();
            }
            assertEquals("Unknown argument: --controler=" + TestSuiteEnvironment.getServerAddress() + ":" + (TestSuiteEnvironment.getServerPort() - 1), line);
        }
        assertTrue(exitCode != 0);
    }
}
