/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.process.internal.launcher;

import org.gradle.internal.classloader.FilteringClassLoader;
import org.gradle.process.internal.child.EncodedStream;

import java.io.DataInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The main entry point for a worker process that is using the system ClassLoader strategy. Reads worker configuration and a serialized worker action from stdin,
 * sets up the worker ClassLoader, and then delegates to {@link org.gradle.process.internal.child.SystemApplicationClassLoaderWorker} to deserialize and execute the action.
 */
public class GradleWorkerMain {
    public void run() throws Exception {
        DataInputStream instr = new DataInputStream(new EncodedStream.EncodedInput(System.in));

        // Read shared packages
        int sharedPackagesCount = instr.readInt();
        List<String> sharedPackages = new ArrayList<String>(sharedPackagesCount);
        for (int i = 0; i < sharedPackagesCount; i++) {
            sharedPackages.add(instr.readUTF());
        }

        // Read worker implementation classpath
        int classPathLength = instr.readInt();
        URL[] implementationClassPath = new URL[classPathLength];
        for (int i = 0; i < classPathLength; i++) {
            String url = instr.readUTF();
            implementationClassPath[i] = new URL(url);
        }

        // Set up worker ClassLoader
        FilteringClassLoader filteringClassLoader = new FilteringClassLoader(getClass().getClassLoader());
        for (String sharedPackage : sharedPackages) {
            filteringClassLoader.allowPackage(sharedPackage);
        }
        URLClassLoader classLoader = new URLClassLoader(implementationClassPath, filteringClassLoader);

        Class<? extends Callable> workerClass = classLoader.loadClass("org.gradle.process.internal.child.SystemApplicationClassLoaderWorker").asSubclass(Callable.class);
        Callable<Void> main = workerClass.getConstructor(DataInputStream.class).newInstance(instr);
        main.call();
    }

    public static void main(String[] args) {
        try {
            new GradleWorkerMain().run();
            System.exit(0);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
