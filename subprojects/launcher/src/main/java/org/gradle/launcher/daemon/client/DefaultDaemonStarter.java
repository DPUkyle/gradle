/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.launcher.daemon.client;

import org.gradle.api.GradleException;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.internal.classpath.DefaultGradleDistributionLocator;
import org.gradle.api.internal.classpath.DefaultModuleRegistry;
import org.gradle.api.internal.classpath.Module;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.serialize.FlushableEncoder;
import org.gradle.internal.serialize.kryo.KryoBackedEncoder;
import org.gradle.launcher.daemon.DaemonExecHandleBuilder;
import org.gradle.launcher.daemon.bootstrap.DaemonGreeter;
import org.gradle.launcher.daemon.bootstrap.DaemonOutputConsumer;
import org.gradle.launcher.daemon.bootstrap.GradleDaemon;
import org.gradle.launcher.daemon.configuration.DaemonParameters;
import org.gradle.launcher.daemon.diagnostics.DaemonStartupInfo;
import org.gradle.launcher.daemon.registry.DaemonDir;
import org.gradle.process.internal.ExecHandle;
import org.gradle.process.internal.child.EncodedStream;
import org.gradle.util.Clock;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GFileUtils;
import org.gradle.util.GradleVersion;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class DefaultDaemonStarter implements DaemonStarter {

    private static final Logger LOGGER = Logging.getLogger(DefaultDaemonStarter.class);

    private final DaemonDir daemonDir;
    private final DaemonParameters daemonParameters;
    private final DaemonGreeter daemonGreeter;
    private final DaemonStartListener listener;
    private final JvmVersionValidator versionValidator;

    public DefaultDaemonStarter(DaemonDir daemonDir, DaemonParameters daemonParameters, DaemonGreeter daemonGreeter, DaemonStartListener listener, JvmVersionValidator versionValidator) {
        this.daemonDir = daemonDir;
        this.daemonParameters = daemonParameters;
        this.daemonGreeter = daemonGreeter;
        this.listener = listener;
        this.versionValidator = versionValidator;
    }

    public DaemonStartupInfo startDaemon() {
        String daemonUid = UUID.randomUUID().toString();

        ModuleRegistry registry = new DefaultModuleRegistry();
        ClassPath classpath;
        List<File> searchClassPath;
        if (new DefaultGradleDistributionLocator().getGradleHome() != null) {
            // When running from a Gradle distro, only need launcher jar. The daemon can find everything from there.
            classpath = registry.getModule("gradle-launcher").getImplementationClasspath();
            searchClassPath = Collections.emptyList();
        } else {
            // When not running from a Gradle distro, need runtime impl for launcher plus the search path to look for other modules
            classpath = new DefaultClassPath();
            for (Module module : registry.getModule("gradle-launcher").getAllRequiredModules()) {
                classpath = classpath.plus(module.getClasspath());
            }
            searchClassPath = registry.getAdditionalClassPath().getAsFiles();
        }
        if (classpath.isEmpty()) {
            throw new IllegalStateException("Unable to construct a bootstrap classpath when starting the daemon");
        }

        versionValidator.validate(daemonParameters);

        List<String> daemonArgs = new ArrayList<String>();
        daemonArgs.add(daemonParameters.getEffectiveJvm().getJavaExecutable().getAbsolutePath());

        List<String> daemonOpts = daemonParameters.getEffectiveJvmArgs();
        daemonArgs.addAll(daemonOpts);
        daemonArgs.add("-cp");
        daemonArgs.add(CollectionUtils.join(File.pathSeparator, classpath.getAsFiles()));

        if (Boolean.getBoolean("org.gradle.daemon.debug")) {
            daemonArgs.add("-Xdebug");
            daemonArgs.add("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005");
        }
        LOGGER.debug("Using daemon args: {}", daemonArgs);

        daemonArgs.add(GradleDaemon.class.getName());
        // Version isn't used, except by a human looking at the output of jps.
        daemonArgs.add(GradleVersion.current().getVersion());

        // Serialize configuration to daemon via the process' stdin
        ByteArrayOutputStream serializedConfig = new ByteArrayOutputStream();
        FlushableEncoder encoder = new KryoBackedEncoder(new EncodedStream.EncodedOutput(serializedConfig));
        try {
            encoder.writeString(daemonParameters.getGradleUserHomeDir().getAbsolutePath());
            encoder.writeString(daemonDir.getBaseDir().getAbsolutePath());
            encoder.writeSmallInt(daemonParameters.getIdleTimeout());
            encoder.writeString(daemonUid);
            encoder.writeSmallInt(daemonOpts.size());
            for (String daemonOpt : daemonOpts) {
                encoder.writeString(daemonOpt);
            }
            encoder.writeSmallInt(searchClassPath.size());
            for (File file : searchClassPath) {
                encoder.writeString(file.getAbsolutePath());
            }
            encoder.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ByteArrayInputStream stdInput = new ByteArrayInputStream(serializedConfig.toByteArray());

        DaemonStartupInfo daemonInfo = startProcess(daemonArgs, daemonDir.getVersionedDir(), stdInput);
        listener.daemonStarted(daemonInfo);
        return daemonInfo;
    }

    private DaemonStartupInfo startProcess(List<String> args, File workingDir, InputStream stdInput) {
        LOGGER.info("Starting daemon process: workingDir = {}, daemonArgs: {}", workingDir, args);
        Clock clock = new Clock();
        try {
            GFileUtils.mkdirs(workingDir);

            DaemonOutputConsumer outputConsumer = new DaemonOutputConsumer(stdInput);
            ExecHandle handle = new DaemonExecHandleBuilder().build(args, workingDir, outputConsumer);

            handle.start();
            LOGGER.debug("Gradle daemon process is starting. Waiting for the daemon to detach...");
            handle.waitForFinish();
            LOGGER.debug("Gradle daemon process is now detached.");

            return daemonGreeter.parseDaemonOutput(outputConsumer.getProcessOutput());
        } catch (GradleException e) {
            throw e;
        } catch (Exception e) {
            throw new GradleException("Could not start Gradle daemon.", e);
        } finally {
            LOGGER.info("An attempt to start the daemon took {}.", clock.getTime());
        }
    }

}
