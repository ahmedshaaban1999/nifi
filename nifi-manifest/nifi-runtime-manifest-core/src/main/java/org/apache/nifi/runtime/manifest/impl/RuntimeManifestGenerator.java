/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.runtime.manifest.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.nifi.c2.protocol.component.api.BuildInfo;
import org.apache.nifi.c2.protocol.component.api.RuntimeManifest;
import org.apache.nifi.extension.manifest.parser.ExtensionManifestParser;
import org.apache.nifi.extension.manifest.parser.jackson.JacksonExtensionManifestParser;
import org.apache.nifi.runtime.manifest.ExtensionManifestProvider;
import org.apache.nifi.runtime.manifest.RuntimeManifestSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Runner class to be called during the build to generate a runtime manifest json file from a directory where
 * all the extension-manifest.xml files have been unpacked.
 */
public class RuntimeManifestGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeManifestGenerator.class);

    private static final String PROJECT_VERSION_PROPERTY = "Project-Version";
    private static final String BUILD_REVISION = "Build-Revision";
    private static final String BUILD_TIMESTAMP = "Build-Timestamp";
    private static final String BUILD_JDK = "Build-Jdk";
    private static final String BUILD_JDK_VENDOR = "Build-Jdk-Vendor";

    private final File extensionManifestBaseDir;
    private final File buildPropertiesFile;
    private final File runtimeManifestFile;
    private final String runtimeManifestId;

    public RuntimeManifestGenerator(final File extensionManifestBaseDir,
                                    final File buildPropertiesFile,
                                    final File runtimeManifestFile,
                                    final String runtimeManifestId) {
        this.extensionManifestBaseDir = extensionManifestBaseDir;
        this.buildPropertiesFile = buildPropertiesFile;
        this.runtimeManifestFile = runtimeManifestFile;
        this.runtimeManifestId = runtimeManifestId;
    }

    public void execute() throws IOException {
        final ExtensionManifestProvider extensionManifestProvider = createExtensionManifestProvider();

        final Properties buildProperties = createBuildProperties();
        final String runtimeVersion = buildProperties.getProperty(PROJECT_VERSION_PROPERTY);
        final String buildRevision = buildProperties.getProperty(BUILD_REVISION);
        final String buildTimestamp = buildProperties.getProperty(BUILD_TIMESTAMP);
        final String buildJdk = buildProperties.getProperty(BUILD_JDK);
        final String buildJdkVendor = buildProperties.getProperty(BUILD_JDK_VENDOR);

        final BuildInfo buildInfo = new BuildInfo();
        buildInfo.setVersion(runtimeVersion);
        buildInfo.setRevision(buildRevision);
        buildInfo.setTimestamp(Long.valueOf(buildTimestamp));
        buildInfo.setCompiler(buildJdkVendor + " " + buildJdk);

        final RuntimeManifest runtimeManifest = new StandardRuntimeManifestBuilder()
                .identifier(runtimeManifestId)
                .version(runtimeVersion)
                .runtimeType("nifi")
                .buildInfo(buildInfo)
                .addBundles(extensionManifestProvider.getExtensionManifests())
                .schedulingDefaults(SchedulingDefaultsFactory.getNifiSchedulingDefaults())
                .build();

        final RuntimeManifestSerializer runtimeManifestSerializer = createRuntimeManifestSerializer();
        try (final OutputStream outputStream = new FileOutputStream(runtimeManifestFile)) {
            runtimeManifestSerializer.write(runtimeManifest, outputStream);
        }
    }

    private ExtensionManifestProvider createExtensionManifestProvider() {
        final ExtensionManifestParser extensionManifestParser = new JacksonExtensionManifestParser();
        return new DirectoryExtensionManifestProvider(extensionManifestBaseDir, extensionManifestParser);
    }

    private Properties createBuildProperties() throws IOException {
        final Properties properties = new Properties();
        try (final InputStream inputStream = new FileInputStream(buildPropertiesFile)) {
            properties.load(inputStream);
        }
        return properties;
    }

    private RuntimeManifestSerializer createRuntimeManifestSerializer() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        final ObjectWriter objectWriter = objectMapper.writerWithDefaultPrettyPrinter();
        return new JacksonRuntimeManifestSerializer(objectWriter);
    }

    /**
     * Called from maven-exec-plugin during build of nifi-runtime-manifest.
     */
    public static void main(String[] args) throws IOException {
        if (args == null || args.length != 4) {
            System.out.println("USAGE: <extension-manifest-base-dir> <build-props-file> <output-file> <manifest-id>");
            return;
        }

        final File extensionManifestBaseDir = new File(args[0]);
        final File buildPropertiesFile = new File(args[1]);
        final File runtimeManifestFile = new File(args[2]);
        final String runtimeManifestId = args[3];

        final File runtimeManifestDir = runtimeManifestFile.getParentFile();
        if (runtimeManifestDir != null) {
            runtimeManifestDir.mkdirs();
        }

        LOGGER.info("Writing runtime manifest to: {}", runtimeManifestFile.getAbsolutePath());

        final RuntimeManifestGenerator runner = new RuntimeManifestGenerator(
                extensionManifestBaseDir, buildPropertiesFile, runtimeManifestFile, runtimeManifestId);
        runner.execute();
    }
}
