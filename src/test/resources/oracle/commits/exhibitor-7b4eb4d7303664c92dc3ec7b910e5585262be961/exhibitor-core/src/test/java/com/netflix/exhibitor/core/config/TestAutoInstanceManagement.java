package com.netflix.exhibitor.core.config;

import com.google.common.io.Files;
import com.netflix.curator.test.DirectoryUtils;
import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.config.filesystem.FileSystemPseudoLock;
import com.netflix.exhibitor.core.state.AutoInstanceManagement;
import com.netflix.exhibitor.core.state.InstanceStateTypes;
import com.netflix.exhibitor.core.state.MonitorRunningInstance;
import junit.framework.Assert;
import org.mockito.Mockito;
import org.testng.annotations.Test;
import java.io.File;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestAutoInstanceManagement
{
    @Test
    public void     testContentionAddNewInstance() throws Exception
    {
        String                      name = PropertyBasedInstanceConfig.toName(IntConfigs.AUTO_MANAGE_INSTANCES, PropertyBasedInstanceConfig.ROOT_PROPERTY_PREFIX);
        Properties                  properties = new Properties();
        properties.setProperty(name, "1");
        final ConfigCollection      config = new PropertyBasedInstanceConfig(properties, new Properties());
        final File                  tempDirectory = Files.createTempDir();

        final AtomicBoolean         management1DidWork = new AtomicBoolean(false);
        final AtomicBoolean         management2DidWork = new AtomicBoolean(false);
        final CountDownLatch        lockAttemptsLatch = new CountDownLatch(2);
        final CountDownLatch        isLockedLatch = new CountDownLatch(1);
        final CountDownLatch        management2DoneLatch = new CountDownLatch(1);
        final CountDownLatch        canContinueLatch = new CountDownLatch(1);
        try
        {
            ConfigProvider              configProvider = new ConfigProvider()
            {
                private volatile LoadedInstanceConfig loadedInstanceConfig;

                @Override
                public LoadedInstanceConfig loadConfig() throws Exception
                {
                    loadedInstanceConfig = new LoadedInstanceConfig(config, 0);
                    return loadedInstanceConfig;
                }

                @Override
                public LoadedInstanceConfig storeConfig(ConfigCollection config, long compareLastModified) throws Exception
                {
                    loadedInstanceConfig = new LoadedInstanceConfig(config, 0);
                    return loadedInstanceConfig;
                }

                @Override
                public void writeInstanceHeartbeat(String instanceHostname) throws Exception
                {
                }

                @Override
                public long getLastHeartbeatForInstance(String instanceHostname) throws Exception
                {
                    return 0;
                }

                @Override
                public PseudoLock newPseudoLock(String prefix) throws Exception
                {
                    return new FileSystemPseudoLock(tempDirectory, prefix, 600000, 1000, 1000)
                    {
                        @Override
                        public boolean lock(long maxWait, TimeUnit unit) throws Exception
                        {
                            lockAttemptsLatch.countDown();
                            boolean locked = super.lock(maxWait, unit);
                            isLockedLatch.countDown();
                            return locked;
                        }

                        @Override
                        public void unlock() throws Exception
                        {
                            canContinueLatch.await();
                            super.unlock();
                        }
                    };
                }
            };

            MockExhibitorInstance           exhibitorInstance = new MockExhibitorInstance("test", configProvider);
            Exhibitor                       exhibitor = exhibitorInstance.getMockExhibitor();

            MonitorRunningInstance          monitorRunningInstance = Mockito.mock(MonitorRunningInstance.class);
            Mockito.when(monitorRunningInstance.getCurrentInstanceState()).thenReturn(InstanceStateTypes.NOT_SERVING);
            Mockito.when(exhibitor.getMonitorRunningInstance()).thenReturn(monitorRunningInstance);

            final AutoInstanceManagement          management1 = new AutoInstanceManagement(exhibitor)
            {
                @Override
                protected void doWork() throws Exception
                {
                    management1DidWork.set(true);
                    super.doWork();
                }
            };
            Executors.newSingleThreadExecutor().submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        management1.call();
                        return null;
                    }
                }
            );
            Assert.assertTrue(isLockedLatch.await(5, TimeUnit.SECONDS));

            final AutoInstanceManagement    management2 = new AutoInstanceManagement(exhibitor)
            {
                @Override
                protected void doWork() throws Exception
                {
                    management2DidWork.set(true);
                    super.doWork();
                }
            };
            Executors.newSingleThreadExecutor().submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        management2.call();
                        management2DoneLatch.countDown();
                        return null;
                    }
                }
            );
            Assert.assertTrue(lockAttemptsLatch.await(5, TimeUnit.SECONDS));
            canContinueLatch.countDown();

            Assert.assertTrue(management2DoneLatch.await(5, TimeUnit.SECONDS));
            Assert.assertTrue(management1DidWork.get());
            Assert.assertFalse(management2DidWork.get());
        }
        finally
        {
            DirectoryUtils.deleteRecursively(tempDirectory);
        }
    }

    @Test
    public void     testSimpleAddNewInstance() throws Exception
    {
        String                      name = PropertyBasedInstanceConfig.toName(IntConfigs.AUTO_MANAGE_INSTANCES, PropertyBasedInstanceConfig.ROOT_PROPERTY_PREFIX);
        Properties                  properties = new Properties();
        properties.setProperty(name, "1");
        final ConfigCollection      config = new PropertyBasedInstanceConfig(properties, new Properties());
        final File                  tempDirectory = Files.createTempDir();

        try
        {
            ConfigProvider              configProvider = new ConfigProvider()
            {
                @Override
                public LoadedInstanceConfig loadConfig() throws Exception
                {
                    return new LoadedInstanceConfig(config, 0);
                }

                @Override
                public LoadedInstanceConfig storeConfig(ConfigCollection config, long compareLastModified) throws Exception
                {
                    return new LoadedInstanceConfig(config, 0);
                }

                @Override
                public void writeInstanceHeartbeat(String instanceHostname) throws Exception
                {
                }

                @Override
                public long getLastHeartbeatForInstance(String instanceHostname) throws Exception
                {
                    return 0;
                }

                @Override
                public PseudoLock newPseudoLock(String prefix) throws Exception
                {
                    return new FileSystemPseudoLock(tempDirectory, prefix, 10000, 1000, 1000);
                }
            };

            MockExhibitorInstance       exhibitorInstance = new MockExhibitorInstance("test", configProvider);
            Exhibitor                   exhibitor = exhibitorInstance.getMockExhibitor();

            MonitorRunningInstance      monitorRunningInstance = Mockito.mock(MonitorRunningInstance.class);
            Mockito.when(monitorRunningInstance.getCurrentInstanceState()).thenReturn(InstanceStateTypes.NOT_SERVING);
            Mockito.when(exhibitor.getMonitorRunningInstance()).thenReturn(monitorRunningInstance);

            AutoInstanceManagement      management = new AutoInstanceManagement(exhibitor);
            management.call();
        }
        finally
        {
            DirectoryUtils.deleteRecursively(tempDirectory);
        }
    }

    @Test
    public void     testSimpleRemoveInstance() throws Exception
    {
        Properties                  properties = new Properties();
        properties.setProperty(PropertyBasedInstanceConfig.toName(IntConfigs.AUTO_MANAGE_INSTANCES, PropertyBasedInstanceConfig.ROOT_PROPERTY_PREFIX), "1");
        properties.setProperty(PropertyBasedInstanceConfig.toName(StringConfigs.SERVERS_SPEC, PropertyBasedInstanceConfig.ROOT_PROPERTY_PREFIX), "1:test,2:dead");

        final ConfigCollection      config = new PropertyBasedInstanceConfig(properties, new Properties());
        final File                  tempDirectory = Files.createTempDir();

        final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(1);
        try
        {
            ConfigProvider              configProvider = new ConfigProvider()
            {
                @Override
                public LoadedInstanceConfig loadConfig() throws Exception
                {
                    return new LoadedInstanceConfig(config, 0);
                }

                @Override
                public LoadedInstanceConfig storeConfig(ConfigCollection config, long compareLastModified) throws Exception
                {
                    queue.put(config.getRollingConfig().getString(StringConfigs.SERVERS_SPEC));
                    return new LoadedInstanceConfig(config, 0);
                }

                @Override
                public void writeInstanceHeartbeat(String instanceHostname) throws Exception
                {
                }

                @Override
                public long getLastHeartbeatForInstance(String instanceHostname) throws Exception
                {
                    return instanceHostname.equals("dead") ? 0 : System.currentTimeMillis();
                }

                @Override
                public PseudoLock newPseudoLock(String prefix) throws Exception
                {
                    return new FileSystemPseudoLock(tempDirectory, prefix, 10000, 1000, 1000);
                }
            };

            MockExhibitorInstance       exhibitorInstance = new MockExhibitorInstance("test", configProvider);
            Exhibitor                   exhibitor = exhibitorInstance.getMockExhibitor();

            MonitorRunningInstance      monitorRunningInstance = Mockito.mock(MonitorRunningInstance.class);
            Mockito.when(monitorRunningInstance.getCurrentInstanceState()).thenReturn(InstanceStateTypes.NOT_SERVING);
            Mockito.when(exhibitor.getMonitorRunningInstance()).thenReturn(monitorRunningInstance);

            AutoInstanceManagement      management = new AutoInstanceManagement(exhibitor);
            management.call();
        }
        finally
        {
            DirectoryUtils.deleteRecursively(tempDirectory);
        }

        String newServersList = queue.poll(5, TimeUnit.SECONDS);
        Assert.assertEquals("1:test", newServersList);
    }
}
