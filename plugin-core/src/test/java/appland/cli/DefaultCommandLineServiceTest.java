package appland.cli;

import appland.AppMapBaseTest;
import appland.files.AppMapFiles;
import appland.settings.AppMapApplicationSettingsService;
import appland.settings.AppMapSettingsListener;
import appland.testRules.ResetIdeHttpProxyRule;
import appland.utils.ModuleTestUtils;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.VfsTestUtil;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.ThrowableConsumer;
import com.intellij.util.net.HttpConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.*;
import org.junit.rules.TestRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

@SuppressWarnings("removal")
public class DefaultCommandLineServiceTest extends AppMapBaseTest {
    @Rule
    public final TestRule resetProxyRule = new ResetIdeHttpProxyRule();

    @Override
    protected TempDirTestFixture createTempDirTestFixture() {
        return new TempDirTestFixtureImpl();
    }

    @Before
    public void setupListener() {
        TestAppLandDownloadService.ensureDownloaded();

        RegisterContentRootsActivity.listenForContentRootChanges(getProject(), getTestRootDisposable());
        AppMapApplicationSettingsService.getInstance().setApiKey("api-key");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            AppLandCommandLineService.getInstance().stopAll(30_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            addSuppressedException(e);
        } finally {
            super.tearDown();
        }
    }

    @Test
    @Ignore("flaky test")
    public void directoryTree() throws Exception {
        // fixme this test is failing oddly on Windows CI, remove when we made a proper fix
        Assume.assumeTrue(SystemInfo.isUnix);

        var service = AppLandCommandLineService.getInstance();

        var parentDir = myFixture.createFile("test.txt", "").getParent();
        assertNotNull(parentDir);

        withContentRoot(parentDir, () -> {
            assertFalse("Service must not execute for a directory without appmap.yaml", service.isRunning(parentDir, false));

            // creating an appmap.yml file must trigger the launch of the matching AppMap processes
            var nestedDir = myFixture.addFileToProject("parent/child/file.txt", "").getParent().getVirtualFile();
            assertNotNull(nestedDir);
            createAppMapYaml(nestedDir, "tmp/appmap");
            waitForProcessStatus(true, nestedDir, true);
            assertActiveRoots(nestedDir);

            createAppMapYaml(parentDir, "tmp/appmap");
            waitForProcessStatus(true, parentDir, true);
            assertTrue("Service must launch with appmap.yaml present", service.isRunning(parentDir, true));
            assertTrue("Service must launch with appmap.yaml present", service.isRunning(parentDir, false));
            assertActiveRoots(parentDir, nestedDir);

            assertTrue("Processes of child directories must keep running", service.isRunning(nestedDir, true));
            assertTrue(service.isRunning(nestedDir, false));
            assertActiveRoots(parentDir, nestedDir);

            service.stop(parentDir, true);
            service.stop(nestedDir, true);

            waitForProcessStatus(false, parentDir, true);
            waitForProcessStatus(false, parentDir, false);
            waitForProcessStatus(false, nestedDir, true);
            waitForProcessStatus(false, nestedDir, false);
        });
    }

    @Test
    public void directoryTreeWatchedSubdir() throws Exception {
        // This test fails on Windows, because it's unable to delete the appmap directory.
        // Maybe the scanner and indexer aren't being stopped properly?
        Assume.assumeTrue(SystemInfo.isUnix);

        var tempDir = myFixture.createFile("test.txt", "").getParent();
        withContentRoot(tempDir, () -> {
            var service = AppLandCommandLineService.getInstance();
            createAppMapYaml(tempDir, "tmp/appmaps");
            waitForProcessStatus(true, tempDir, true);
            assertActiveRoots(tempDir);

            assertTrue(service.isRunning(tempDir, false));

            var tempDirNioPath = LocalFileSystem.getInstance().getNioPath(tempDir);
            assertNotNull(tempDirNioPath);

            var appMapDirNioPath = tempDirNioPath.resolve("tmp/appmaps");
            assertTrue("Configured AppMap dir must be created: " + appMapDirNioPath, Files.isDirectory(appMapDirNioPath));
        });
    }

    @Test
    public void environmentPassing() throws ExecutionException, InterruptedException, IOException {
        final var settings = AppMapApplicationSettingsService.getInstance();
        settings.setCliEnvironment(Map.of("FOO", "BAR", "BAZ", "QUX"));
        // use "cmd /c set" on windows, "env" on unix
        Path workingDir = Path.of(".");
        final var process = (SystemInfo.isWindows
                ? DefaultCommandLineService.startProcess(workingDir, "cmd", "/c", "set")
                : DefaultCommandLineService.startProcess(workingDir, "env")
        ).getProcess();

        process.waitFor(5, TimeUnit.SECONDS);

        // read the output of the process
        final var output = new String(process.getInputStream().readAllBytes());

        // check that the environment variables are passed to the process
        assertTrue(output.contains("FOO=BAR"));
        assertTrue(output.contains("BAZ=QUX"));
    }

    @Test
    @Ignore("flaky test")
    public void siblingDirectories() throws Exception {
        var dirA = myFixture.addFileToProject("parentA/file.txt", "").getParent().getVirtualFile();
        var dirB = myFixture.addFileToProject("parentB/file.txt", "").getParent().getVirtualFile();

        var service = AppLandCommandLineService.getInstance();

        // no appmap.yml files -> no processes
        addContentRootAndLaunchService(dirA);
        addContentRootAndLaunchService(dirB);
        assertEmptyRoots();

        // appmap.yml for dirA
        createAppMapYaml(dirA);
        addContentRootAndLaunchService(dirA);
        assertTrue(service.isRunning(dirA, true));
        assertFalse(service.isRunning(dirB, true));

        // appmap.yml for dirB and dirA
        createAppMapYaml(dirB);
        addContentRootAndLaunchService(dirB);
        assertTrue(service.isRunning(dirA, true));
        assertTrue(service.isRunning(dirB, true));

        assertActiveRoots(dirA, dirB);

        service.stopAll(10_000, TimeUnit.MILLISECONDS);

        var debugInfo = service.toString();
        assertFalse("No services expected for parentA: " + debugInfo, service.isRunning(dirA, true));
        assertFalse("No services expected for parentB: " + debugInfo, service.isRunning(dirB, true));
    }

    @Test
    public void contentRootUpdates() throws Exception {
        Assume.assumeFalse("On windows, it fails with java.io.IOException: Cannot delete ...", SystemInfo.isWindows);

        var topLevelDir = myFixture.addFileToProject("file.txt", "").getParent().getVirtualFile();
        var newRootA = myFixture.addFileToProject("parentA/file.txt", "").getParent().getVirtualFile();
        var nestedRootA = myFixture.addFileToProject("parentA/subDir/file.txt", "").getParent().getVirtualFile();
        var newRootB = myFixture.addFileToProject("parentB/file.txt", "").getParent().getVirtualFile();

        withContentRoot(topLevelDir, () -> {
            createAppMapYaml(newRootA);
            waitForProcessStatus(true, newRootA, true);

            createAppMapYaml(newRootB);
            waitForProcessStatus(true, newRootB, true);

            // add new roots and assert that the new processes are launched
            var condition = ProjectRefreshUtil.newProjectRefreshCondition(getTestRootDisposable());
            ModuleTestUtils.withContentRoots(getModule(), List.of(newRootA, nestedRootA, newRootB), () -> {
                assertTrue(condition.await(30, TimeUnit.SECONDS));

                assertActiveRoots(newRootA, newRootB);
            });
        });
    }

    @Test
    public void installCommandLineHasPty() {
        var installCommand = AppLandCommandLineService.getInstance().createInstallCommand(Paths.get("/"), "java");
        assertTrue("The install command must have PTY", installCommand instanceof PtyCommandLine);
    }

    @Test
    public void directoryRefreshAfterAppMapIndexing() throws Throwable {
        Assume.assumeFalse("AppMap processes don't terminate reliably on Windows", SystemInfo.isWindows);

        var appMapConfig = myFixture.copyFileToProject("projects/without_existing_index/appmap.yml", "test-project/appmap.yml");
        withContentRoot(appMapConfig.getParent(), () -> {
            // copying AppMaps into a watched directory must trigger a file refresh based on the output of the AppMap process
            var refreshCondition = TestCommandLineService.newVfsRefreshCondition(getProject(), getTestRootDisposable());
            myFixture.copyDirectoryToProject("projects/without_existing_index", "test-project");
            assertTrue(refreshCondition.await(30, TimeUnit.SECONDS));

            var refreshedFiles = TestCommandLineService.getInstance().getRefreshedFiles();
            assertSize(1, refreshedFiles);
            var refreshedPath = refreshedFiles.iterator().next();
            assertTrue("The parent directory of the AppMap must be refreshed", refreshedPath.endsWith("appmap"));
        });
    }

    @Test
    public void indexerProcessRestart() throws Exception {
        setupAndAssertProcessRestart(getIndexerFunction);
    }

    @Test
    public void scannerProcessRestart() throws Exception {
        try {
            AppMapApplicationSettingsService.getInstance().setEnableScanner(true);

            setupAndAssertProcessRestart(getScannerFunction);
        } finally {
            AppMapApplicationSettingsService.getInstance().setEnableScanner(false);
        }
    }

    @Test
    public void restartAfterApiKeyChange() throws Exception {
        // we're installing the listener for api key changes just for this test to avoid side effects inside the test
        // setup and in our other tests
        ApplicationManager.getApplication().getMessageBus()
                .connect(getTestRootDisposable())
                .subscribe(AppMapSettingsListener.TOPIC, new RestartServicesAfterApiChangeListener());

        var tempDir = myFixture.createFile("test.txt", "").getParent();
        withContentRoot(tempDir, () -> {
            createAppMapYaml(tempDir, "tmp/appmap");
            waitForProcessStatus(true, tempDir, true);
            assertActiveRoots(tempDir);

            // restart after change
            waitForProcessRestart(tempDir, getIndexerFunction, processHandler -> {
                AppMapApplicationSettingsService.getInstance().setApiKeyNotifying("new-api-key");
            });
            assertActiveRoots(tempDir);

            // restart after sign out
            waitForProcessRestart(tempDir, getIndexerFunction, processHandler -> {
                AppMapApplicationSettingsService.getInstance().setApiKeyNotifying(null);
            });
            assertActiveRoots(tempDir);
        });
    }

    @Test
    public void scannerSettingDefault() throws Exception {
        var settings = AppMapApplicationSettingsService.getInstance();
        assertFalse("Scanner must be turned off by default", settings.isEnableScanner());

        var tempDir = myFixture.createFile("test.txt", "").getParent();
        withContentRoot(tempDir, () -> {
            createAppMapYaml(tempDir, "tmp/appmap");
            waitForProcessStatus(true, tempDir, true);
            assertActiveRoots(tempDir);

            for (var processes : TestCommandLineService.getInstance().processes.values()) {
                assertNull("Scanner must not be launched if the setting is off", processes.getScanner());

                assertNotNull("Indexer must not be controlled by scanner setting", processes.getIndexer());
            }
        });
    }

    @Test
    public void appmapYamlTrigger() throws Exception {
        var newRootA = myFixture.addFileToProject("parentA/file.txt", "").getParent().getVirtualFile();

        final var rootRefreshCondition = ProjectRefreshUtil.newProjectRefreshCondition(getTestRootDisposable());
        withContentRoot(newRootA, () -> {
            assertTrue(rootRefreshCondition.await(30, TimeUnit.SECONDS));

            // no watched roots because there's no appmap.yml
            assertEmptyRoots();

            // creating an appmap.yml file in a content root must trigger a refresh and the start of the CLI binaries
            var appMapYaml = createAppMapYaml(newRootA);
            waitForProcessStatus(true, newRootA, true);
            assertActiveRoots(newRootA);

            // removing the appmap.yml again must stop the service
            WriteAction.runAndWait(() -> appMapYaml.delete(this));
            waitForProcessStatus(false, newRootA, true);
            assertEmptyRoots();
        });
    }

    @Test
    public void noProxySettings() {
        HttpConfigurable.getInstance().USE_HTTP_PROXY = false;
        HttpConfigurable.getInstance().PROXY_HOST = "my.proxy.host";
        HttpConfigurable.getInstance().PROXY_PORT = 8080;
        HttpConfigurable.getInstance().PROXY_EXCEPTIONS = "localhost*,127.*,*.example.com";

        assertEquals(Map.of(), DefaultCommandLineService.createProxyEnvironment());
    }

    @Test
    public void proxySettingsSocksIsUnsupported() {
        HttpConfigurable.getInstance().USE_HTTP_PROXY = true;
        HttpConfigurable.getInstance().PROXY_TYPE_IS_SOCKS = true;
        HttpConfigurable.getInstance().PROXY_HOST = "my.proxy.host";
        HttpConfigurable.getInstance().PROXY_PORT = 8080;

        assertEquals(Map.of(), DefaultCommandLineService.createProxyEnvironment());
    }

    @Test
    public void proxySettingsEnvironmentNoAuthentication() {
        HttpConfigurable.getInstance().USE_HTTP_PROXY = true;
        HttpConfigurable.getInstance().PROXY_HOST = "my.proxy.host";
        HttpConfigurable.getInstance().PROXY_PORT = 8080;
        HttpConfigurable.getInstance().PROXY_EXCEPTIONS = "localhost*,127.*,*.example.com";

        assertEquals(Map.of(
                "http_proxy", "http://my.proxy.host:8080",
                "https_proxy", "http://my.proxy.host:8080",
                "no_proxy", "localhost*,127.*,*.example.com"
        ), DefaultCommandLineService.createProxyEnvironment());
    }

    @Test
    public void proxySettingsEnvironmentWithAuthentication() {
        HttpConfigurable.getInstance().USE_HTTP_PROXY = true;
        HttpConfigurable.getInstance().PROXY_HOST = "my.proxy.host";
        HttpConfigurable.getInstance().PROXY_PORT = 8080;
        HttpConfigurable.getInstance().KEEP_PROXY_PASSWORD = true;
        HttpConfigurable.getInstance().setProxyLogin("username");
        HttpConfigurable.getInstance().setPlainProxyPassword("secure_password");
        HttpConfigurable.getInstance().PROXY_EXCEPTIONS = "localhost*,127.*,*.example.com";

        assertEquals(Map.of(
                "http_proxy", "http://username:secure_password@my.proxy.host:8080",
                "https_proxy", "http://username:secure_password@my.proxy.host:8080",
                "no_proxy", "localhost*,127.*,*.example.com"
        ), DefaultCommandLineService.createProxyEnvironment());
    }

    @Test
    public void proxySettingsEnvironmentWithEmptyPasswordAuthentication() {
        HttpConfigurable.getInstance().USE_HTTP_PROXY = true;
        HttpConfigurable.getInstance().PROXY_HOST = "my.proxy.host";
        HttpConfigurable.getInstance().PROXY_PORT = 8080;
        HttpConfigurable.getInstance().KEEP_PROXY_PASSWORD = true;
        HttpConfigurable.getInstance().setProxyLogin("username");
        HttpConfigurable.getInstance().setPlainProxyPassword(null);
        HttpConfigurable.getInstance().PROXY_EXCEPTIONS = "localhost*,127.*,*.example.com";

        assertEquals(Map.of(
                "http_proxy", "http://username:@my.proxy.host:8080",
                "https_proxy", "http://username:@my.proxy.host:8080",
                "no_proxy", "localhost*,127.*,*.example.com"
        ), DefaultCommandLineService.createProxyEnvironment());
    }

    @Test
    public void proxySettingsEnvironmentMinimalSettings() {
        HttpConfigurable.getInstance().USE_HTTP_PROXY = true;
        HttpConfigurable.getInstance().PROXY_HOST = "my.proxy.host";
        HttpConfigurable.getInstance().PROXY_PORT = 8080;
        HttpConfigurable.getInstance().PROXY_EXCEPTIONS = null;

        assertEquals(Map.of(
                "http_proxy", "http://my.proxy.host:8080",
                "https_proxy", "http://my.proxy.host:8080",
                "no_proxy", ""
        ), DefaultCommandLineService.createProxyEnvironment());
    }

    private void setupAndAssertProcessRestart(@NotNull Function<VirtualFile, KillableProcessHandler> processForRoot) throws Exception {
        var root = myFixture.addFileToProject("parentA/file.txt", "").getParent().getVirtualFile();
        withContentRoot(root, () -> {
            createAppMapYaml(root);
            waitForProcessStatus(true, root, true);
            assertActiveRoots(root);

            // if one of the two processes is killed, it has to restart
            waitForProcessRestart(root, processForRoot, DefaultCommandLineServiceTest::terminateProcess);

            // the restarted process must be restarted again when terminated
            waitForProcessRestart(root, processForRoot, DefaultCommandLineServiceTest::terminateProcess);
        });
    }

    private @NotNull VirtualFile createAppMapYaml(@NotNull VirtualFile directory) throws InterruptedException {
        return createAppMapYaml(directory, null);
    }

    private @NotNull VirtualFile createAppMapYaml(@NotNull VirtualFile directory,
                                                  @Nullable String appMapPath) throws InterruptedException {
        // a change to an appmap.yml is only applied if it's located in a content root
        assertNotNull("appmap.yml must be located in a content root", ReadAction.compute(() -> {
            return AppMapFiles.findTopLevelContentRoot(getProject(), directory);
        }));

        var refreshLatch = new CountDownLatch(1);
        var bus = ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable());
        bus.subscribe(AppLandCommandLineListener.TOPIC, (AppLandCommandLineListener) refreshLatch::countDown);
        try {
            var content = appMapPath != null ? "appmap_dir: " + appMapPath + "\n" : "";
            return VfsTestUtil.createFile(directory, "appmap.yml", content);
        } finally {
            // Creating a new appmap.yml file triggers the start of the CLI processes,
            // we have to wait for them to before executing the following tests.
            assertTrue(refreshLatch.await(10, TimeUnit.SECONDS));
        }
    }

    private void assertEmptyRoots() throws InterruptedException {
        assertActiveRoots();
    }

    private void addContentRootAndLaunchService(@NotNull VirtualFile... contentRoots) throws ExecutionException {
        ModuleRootModificationUtil.updateModel(getModule(), model -> {
            for (var contentRoot : contentRoots) {
                model.addContentEntry(contentRoot);
            }
        });

        for (var contentRoot : contentRoots) {
            AppLandCommandLineService.getInstance().start(contentRoot, true);
        }
    }

    /**
     * Asserts that the detected AppLand content roots match the parameters
     */
    private void assertActiveRoots(@NotNull VirtualFile... roots) throws InterruptedException {
        var expectedRoots = Set.of(roots);
        var expectedString = StringUtil.join(expectedRoots, VirtualFile::toString, ", ");

        var errorMessageSupplier = new Supplier<String>() {
            @Override
            public String get() {
                var activeRoots = Set.copyOf(AppLandCommandLineService.getInstance().getActiveRoots());
                var activeRootsString = StringUtil.join(activeRoots, VirtualFile::toString, ", ");
                return expectedRoots.equals(activeRoots)
                        ? null
                        : "Roots don't match. Expected: " + expectedString + ", actual: " + activeRootsString;
            }
        };

        // wait up to 30s for the active roots
        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            if (errorMessageSupplier.get() == null) {
                break;
            }
            Thread.sleep(100);
        }

        var errorMessage = errorMessageSupplier.get();
        if (errorMessage != null) {
            fail(errorMessage);
        }
    }

    /**
     * Wait and retry for 30s until the process status of the directory is matching the expected value.
     *
     * @param expectedIsRunning Expected process status
     * @param directory         Directory to check
     * @param strict            If a strict check should be performed
     */
    private void waitForProcessStatus(boolean expectedIsRunning, @NotNull VirtualFile directory, boolean strict) throws Exception {
        LOG.info("waitForProcessStatus: " + directory);

        var service = AppLandCommandLineService.getInstance();
        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (System.currentTimeMillis() < deadline) {
            if (expectedIsRunning == service.isRunning(directory, strict)) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals("Unexpected process status for directory " + directory + ": " + service, expectedIsRunning, service.isRunning(directory, strict));
    }

    private static void waitForProcessRestart(@NotNull VirtualFile root,
                                              @NotNull Function<VirtualFile, KillableProcessHandler> processForRoot,
                                              @NotNull ThrowableConsumer<KillableProcessHandler, Exception> restartTrigger) throws Exception {
        var oldProcess = processForRoot.apply(root);
        assertNotNull("Process must be available before triggering the restart", oldProcess);

        restartTrigger.consume(oldProcess);

        // wait up to 10s for restart of the indexer process
        boolean restarted = false;
        for (var i = 0; i < 100 && !restarted; i++) {
            var newProcess = processForRoot.apply(root);
            restarted = newProcess != null && !oldProcess.equals(newProcess);
            Thread.sleep(100);
        }

        assertTrue("Process must restart", restarted);
    }

    private static void terminateProcess(@NotNull KillableProcessHandler process) {
        process.destroyProcess();
        process.killProcess();
        assertTrue("Process must terminate", process.waitFor(5_000));
    }

    private static final Function<VirtualFile, KillableProcessHandler> getIndexerFunction = root -> {
        var processes = ((DefaultCommandLineService) AppLandCommandLineService.getInstance()).getProcesses(root);
        return processes == null ? null : processes.getIndexer();
    };

    private static final Function<VirtualFile, KillableProcessHandler> getScannerFunction = root -> {
        var processes = ((DefaultCommandLineService) AppLandCommandLineService.getInstance()).getProcesses(root);
        return processes == null ? null : processes.getScanner();
    };
}