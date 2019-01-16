/*
 * Copyright 2014 NAVER Corp.
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

import com.navercorp.pinpoint.ProductInfo;
import com.navercorp.pinpoint.bootstrap.agentdir.AgentDirectory;
import com.navercorp.pinpoint.bootstrap.classloader.PinpointClassLoaderFactory;
import com.navercorp.pinpoint.bootstrap.classloader.ProfilerLibs;
import com.navercorp.pinpoint.bootstrap.config.DefaultProfilerConfig;
import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;
import com.navercorp.pinpoint.common.Version;
import com.navercorp.pinpoint.common.util.PinpointThreadFactory;
import com.navercorp.pinpoint.common.util.SimpleProperty;
import com.navercorp.pinpoint.common.util.SystemProperty;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 没有public修饰的类只能被同包的类访问到
 *
 * @author Jongho Moon
 */
class PinpointStarter {

    private final BootLogger logger = BootLogger.getLogger(PinpointStarter.class.getName());

    public static final String AGENT_TYPE = "AGENT_TYPE";

    public static final String DEFAULT_AGENT = "DEFAULT_AGENT";
    // 通过制定类的binary name，在运行时通过class loader加载该类，动态生成该类的实例
    // DefaultAgent 包含配置文件的属性提取
    public static final String BOOT_CLASS = "com.navercorp.pinpoint.profiler.DefaultAgent";

    public static final String PLUGIN_TEST_AGENT = "PLUGIN_TEST";
    public static final String PLUGIN_TEST_BOOT_CLASS = "com.navercorp.pinpoint.test.PluginTestAgent";

    private SimpleProperty systemProperty = SystemProperty.INSTANCE;

    private final Map<String, String> agentArgs;
    private final AgentDirectory agentDirectory;
    private final Instrumentation instrumentation;
    private final ClassLoader parentClassLoader;
    private final ModuleBootLoader moduleBootLoader;


    public PinpointStarter(ClassLoader parentClassLoader, Map<String, String> agentArgs,
                           AgentDirectory agentDirectory,
                           Instrumentation instrumentation, ModuleBootLoader moduleBootLoader) {
        //        null == BootstrapClassLoader
//        if (bootstrapClassLoader == null) {
//            throw new NullPointerException("bootstrapClassLoader must not be null");
//        }
        if (agentArgs == null) {
            throw new NullPointerException("agentArgs must not be null");
        }
        if (agentDirectory == null) {
            throw new NullPointerException("agentDirectory must not be null");
        }
        if (instrumentation == null) {
            throw new NullPointerException("instrumentation must not be null");
        }
        this.agentArgs = agentArgs;
        this.parentClassLoader = parentClassLoader;
        this.agentDirectory = agentDirectory;
        this.instrumentation = instrumentation;
        this.moduleBootLoader = moduleBootLoader;

    }

    // 没有修饰符的方法只能被同包的类访问到
    boolean start() {
        // 根据系统环境变量-D... e.g -Dpinpoint.agentId=???
        // pinpoint.agentId=99               获取app的id
        // pinpoint.applicationName=crud     获取app的Name
        final IdValidator idValidator = new IdValidator();
        final String agentId = idValidator.getAgentId();
        if (agentId == null) {
            return false;
        }
        final String applicationName = idValidator.getApplicationName();
        if (applicationName == null) {
            return false;
        }

        final ContainerResolver containerResolver = new ContainerResolver();
        final boolean isContainer = containerResolver.isContainer();

        List<String> pluginJars = agentDirectory.getPlugins();
        // 获取配置文件pinpoint.config的路径
        String configPath = getConfigPath(agentDirectory);
        if (configPath == null) {
            return false;
        }

        // set the path of log file as a system property
        saveLogFilePath(agentDirectory);

        savePinpointVersion();

        try {
            // Is it right to load the configuration in the bootstrap?
            // 使用Spring中占位符解析代码，读取配置文件中的配置
            ProfilerConfig profilerConfig = DefaultProfilerConfig.load(configPath);

            // this is the library list that must be loaded
            final URL[] urls = resolveLib(agentDirectory);
            // 根绝java version的由不同的classloaderfactory生成不同的classloader
            // <<Effective java>> 创建对象？由factory创建对象 ？
            final ClassLoader agentClassLoader = createClassLoader("pinpoint.agent", urls, parentClassLoader);
            if (moduleBootLoader != null) {
                this.logger.info("defineAgentModule");
                moduleBootLoader.defineAgentModule(agentClassLoader, urls);
            }

            final String bootClass = getBootClass();
            AgentBootLoader agentBootLoader = new AgentBootLoader(bootClass, urls, agentClassLoader);
            logger.info("pinpoint agent [" + bootClass + "] starting...");

            // AgentOption描述agent启动时的信息，包括agentID，applicationName，启动时agent自身需要的jar包，插件jar包
            AgentOption option = createAgentOption(agentId, applicationName, isContainer, profilerConfig, instrumentation, pluginJars, agentDirectory);
            // agentBootLoader.boot(option) 通过反射将DefalutAgent实例化，DefaultAgent用于控制agent服务的启动与关闭，
            // DefaultAgent通过成员变量ApplicationContext进行服务的启动与停止
            Agent pinpointAgent = agentBootLoader.boot(option);
            // 调用ApplicationContext.start();
            pinpointAgent.start();
            registerShutdownHook(pinpointAgent);
            logger.info("pinpoint agent started normally.");
        } catch (Exception e) {
            // unexpected exception that did not be checked above
            logger.warn(ProductInfo.NAME + " start failed.", e);
            return false;
        }
        return true;
    }

    private ClassLoader createClassLoader(final String name, final URL[] urls, final ClassLoader parentClassLoader) {
        // ProfilerLibs.PINPOINT_PROFILER_CLASS用于表示profiler各个包的路径，如rpc，aop，inject等包的路径
        if (System.getSecurityManager() != null) {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                public ClassLoader run() {
                    return PinpointClassLoaderFactory.createClassLoader(name, urls, parentClassLoader, ProfilerLibs.PINPOINT_PROFILER_CLASS);
                }
            });
        } else {
            return PinpointClassLoaderFactory.createClassLoader(name, urls, parentClassLoader, ProfilerLibs.PINPOINT_PROFILER_CLASS);
        }
    }

    private String getBootClass() {
        final String agentType = getAgentType().toUpperCase();
        if (PLUGIN_TEST_AGENT.equals(agentType)) {
            return PLUGIN_TEST_BOOT_CLASS;
        }
        return BOOT_CLASS;
    }

    private String getAgentType() {
        String agentType = agentArgs.get(AGENT_TYPE);
        if (agentType == null) {
            return DEFAULT_AGENT;
        }
        return agentType;

    }

    private AgentOption createAgentOption(String agentId, String applicationName, boolean isContainer,
                                          ProfilerConfig profilerConfig,
                                          Instrumentation instrumentation,
                                          List<String> pluginJars,
                                          AgentDirectory agentDirectory) {
        List<String> bootstrapJarPaths = agentDirectory.getBootDir().toList();
        return new DefaultAgentOption(instrumentation, agentId, applicationName, isContainer, profilerConfig, pluginJars, bootstrapJarPaths);
    }

    // for test
    void setSystemProperty(SimpleProperty systemProperty) {
        this.systemProperty = systemProperty;
    }

    private void registerShutdownHook(final Agent pinpointAgent) {
        final Runnable stop = new Runnable() {
            @Override
            public void run() {
                pinpointAgent.stop();
            }
        };
        PinpointThreadFactory pinpointThreadFactory = new PinpointThreadFactory("Pinpoint-shutdown-hook", false);
        Thread thread = pinpointThreadFactory.newThread(stop);
        Runtime.getRuntime().addShutdownHook(thread);
    }


    private void saveLogFilePath(AgentDirectory agentDirectory) {
        String agentLogFilePath = agentDirectory.getAgentLogFilePath();
        logger.info("logPath:" + agentLogFilePath);

        systemProperty.setProperty(ProductInfo.NAME + ".log", agentLogFilePath);
    }

    private void savePinpointVersion() {
        logger.info("pinpoint version:" + Version.VERSION);
        systemProperty.setProperty(ProductInfo.NAME + ".version", Version.VERSION);
    }

    // 获取配置文件的路径
    private String getConfigPath(AgentDirectory agentDirectory) {
        // 如果在-D系统环境变量中写了pinpoint.config配置文件的位置
        final String configName = ProductInfo.NAME + ".config";
        String pinpointConfigFormSystemProperty = systemProperty.getProperty(configName);
        if (pinpointConfigFormSystemProperty != null) {
            logger.info(configName + " systemProperty found. " + pinpointConfigFormSystemProperty);
            return pinpointConfigFormSystemProperty;
        }

        // 如果没有，则根据jar文件的位置获取pinpoint.config配置文件的位置，并返回
        String classPathAgentConfigPath = agentDirectory.getAgentConfigPath();
        if (classPathAgentConfigPath != null) {
            logger.info("classpath " + configName + " found. " + classPathAgentConfigPath);
            return classPathAgentConfigPath;
        }

        logger.info(configName + " file not found.");
        return null;
    }


    private URL[] resolveLib(AgentDirectory classPathResolver) {
        // this method may handle only absolute path,  need to handle relative path (./..agentlib/lib)
        String agentJarFullPath = classPathResolver.getAgentJarFullPath();
        String agentLibPath = classPathResolver.getAgentLibPath();
        List<URL> urlList = resolveLib(classPathResolver.getLibs());
        String agentConfigPath = classPathResolver.getAgentConfigPath();

        if (logger.isInfoEnabled()) {
            logger.info("agent JarPath:" + agentJarFullPath);
            logger.info("agent LibDir:" + agentLibPath);
            for (URL url : urlList) {
                logger.info("agent Lib:" + url);
            }
            logger.info("agent config:" + agentConfigPath);
        }
        return urlList.toArray(new URL[0]);
    }

    private List<URL> resolveLib(List<URL> urlList) {
        if (DEFAULT_AGENT.equalsIgnoreCase(getAgentType())) {
            final List<URL> releaseLib = new ArrayList<URL>(urlList.size());
            for (URL url : urlList) {
                //
                if (!url.toExternalForm().contains("pinpoint-profiler-test")) {
                    releaseLib.add(url);
                }
            }
            return releaseLib;
        } else {
            logger.info("load " + PLUGIN_TEST_AGENT + " lib");
            // plugin test
            return urlList;
        }
    }

}
