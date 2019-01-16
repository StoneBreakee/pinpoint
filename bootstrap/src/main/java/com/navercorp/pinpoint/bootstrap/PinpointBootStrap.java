/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.bootstrap;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import com.navercorp.pinpoint.ProductInfo;
import com.navercorp.pinpoint.bootstrap.agentdir.AgentDirBaseClassPathResolver;
import com.navercorp.pinpoint.bootstrap.agentdir.AgentDirectory;
import com.navercorp.pinpoint.bootstrap.agentdir.BootDir;
import com.navercorp.pinpoint.bootstrap.agentdir.ClassPathResolver;
import com.navercorp.pinpoint.bootstrap.agentdir.JavaAgentPathResolver;
import com.navercorp.pinpoint.common.util.CodeSourceUtils;

/**
 * @author emeroad
 * @author netspider
 */
public class PinpointBootStrap {

    private static final BootLogger logger = BootLogger.getLogger(PinpointBootStrap.class.getName());

    private static final LoadState STATE = new LoadState();

    // 程序入口
    // 通过定义一个代理 -> 即在main方法之前被加载用来按照某种方式监视程序的一个类库（javaagent.jar）。
    // instrumentation提供了一个安装 字节码转换器的挂钩(一旦有类需要加载进来，钩子就会触发字节码转换器进行转换)，必须在main方法运行之前安装这个转换器。
    // (挂钩,转换器)
    // instrumentation 可以用来安装各种各样的挂钩
    // agentArgs 用于接收代理的命令行参数 java -javaagent:javaagent.jar[=args]
    // pinpoint中用args接收参数，以逗号分隔 args: pinpoint.agentId=99,pinpoint.applicationName=pp
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        if (agentArgs == null) {
            agentArgs = "";
        }
        logger.info(ProductInfo.NAME + " agentArgs:" + agentArgs);
        // ？ PinpointBootStrap.class.getClassLoader() 为什么是null？
        logger.info("classLoader:" + PinpointBootStrap.class.getClassLoader());
        logger.info("contextClassLoader:" + Thread.currentThread().getContextClassLoader());
        // Object类位于java.lang包中，因此其类加载器是BootstrapClassLoader,获取其类加载器时值为null
        if (Object.class.getClassLoader() != PinpointBootStrap.class.getClassLoader()) {
            final URL location = CodeSourceUtils.getCodeLocation(PinpointBootStrap.class);
            logger.warn("Invalid pinpoint-bootstrap.jar:" + location);
            return;
        }

        final boolean success = STATE.start();
        if (!success) {
            logger.warn("pinpoint-bootstrap already started. skipping agent loading.");
            return;
        }
        Map<String, String> agentArgsMap = argsToMap(agentArgs);

        // 对于参数 -javaagent:pinpoint-bootstrap-1.8.1-SNAPSHOT.jar=... 等参数通过 MXBean获取并赋值给agentPath
        JavaAgentPathResolver javaAgentPathResolver = JavaAgentPathResolver.newJavaAgentPathResolver();
        String agentPath = javaAgentPathResolver.resolveJavaAgentPath();
        logger.info("JavaAgentPath:" + agentPath);

        // AgentDirBaseClassPathRevolver 用于根据agentPath获取boot,plugins,libs中的jar包并赋值给agentDirectory
        final ClassPathResolver classPathResolver = new AgentDirBaseClassPathResolver(agentPath);
        final AgentDirectory agentDirectory = resolveAgentDir(classPathResolver);
        if (agentDirectory == null) {
            logger.warn("Agent Directory Verify fail. skipping agent loading.");
            logPinpointAgentLoadFail();
            return;
        }
        // bootDir 中包含了
        //   |---pinpoint-commons-1.8.1-SNAPSHOT.jar
        //   |---pinpoint-bootstrap-core-1.8.1-SNAPSHOT.jar
        //   |---pinpoint-bootstrap-core-optional-1.8.1.SNAPSHOT.jar
        //   |---pinpoint-bootstrap-java8-1.8.1-SNAPSHOT.jar
        //   |---pinpoint-annotations-1.8.1-SNAPSHOT.jar
        BootDir bootDir = agentDirectory.getBootDir();
        appendToBootstrapClassLoader(instrumentation, bootDir);

        ClassLoader parentClassLoader = getParentClassLoader();
        final ModuleBootLoader moduleBootLoader = loadModuleBootLoader(instrumentation, parentClassLoader);
        PinpointStarter bootStrap = new PinpointStarter(parentClassLoader, agentArgsMap, agentDirectory, instrumentation, moduleBootLoader);
        if (!bootStrap.start()) {
            logPinpointAgentLoadFail();
        }

    }

    private static ModuleBootLoader loadModuleBootLoader(Instrumentation instrumentation, ClassLoader parentClassLoader) {
        if (!ModuleUtils.isModuleSupported()) {
            return null;
        }
        logger.info("java9 module detected");
        logger.info("ModuleBootLoader start");
        ModuleBootLoader moduleBootLoader = new ModuleBootLoader(instrumentation, parentClassLoader);
        moduleBootLoader.loadModuleSupport();
        return moduleBootLoader;
    }

    private static AgentDirectory resolveAgentDir(ClassPathResolver classPathResolver) {
        try {
            AgentDirectory agentDir = classPathResolver.resolve();
            return agentDir;
        } catch(Exception e) {
            logger.warn("AgentDir resolve fail Caused by:" + e.getMessage(), e);
            return null;
        }
    }


    private static ClassLoader getParentClassLoader() {
        final ClassLoader classLoader = getPinpointBootStrapClassLoader();
        if (classLoader == Object.class.getClassLoader()) {
            logger.info("parentClassLoader:BootStrapClassLoader:" + classLoader );
        } else {
            logger.info("parentClassLoader:" + classLoader);
        }
        return classLoader;
    }

    private static ClassLoader getPinpointBootStrapClassLoader() {
        return PinpointBootStrap.class.getClassLoader();
    }


    private static Map<String, String> argsToMap(String agentArgs) {
        ArgsParser argsParser = new ArgsParser();
        Map<String, String> agentArgsMap = argsParser.parse(agentArgs);
        if (!agentArgsMap.isEmpty()) {
            logger.info("agentParameter:" + agentArgs);
        }
        return agentArgsMap;
    }

    // instrumentation.appendToBootstrapClassLoaderSearch(jarFile) 作用
    private static void appendToBootstrapClassLoader(Instrumentation instrumentation, BootDir bootDir) {
        List<JarFile> jarFiles = bootDir.openJarFiles();
        for (JarFile jarFile : jarFiles) {
            logger.info("appendToBootstrapClassLoader:" + jarFile.getName());
            instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
        }
    }



    private static void logPinpointAgentLoadFail() {
        final String errorLog =
                "*****************************************************************************\n" +
                        "* Pinpoint Agent load failure\n" +
                        "*****************************************************************************";
        System.err.println(errorLog);
    }


}
