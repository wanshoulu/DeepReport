package com.agenthub.w06.checker;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * 构建期测试驱动（0.1.0）
 *
 * 服务器无外网、本地无 junit-platform-console-standalone，改用 JUnit Platform Launcher API
 * 驱动本包全部 Jupiter 测试：全部通过退出码 0，任一失败退出码 1（docker build 随之中止）。
 */
public final class TestMain {

    public static void main(String[] args) throws Exception {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("com.agenthub.w06.checker"))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);
        summary.printTo(out);
        summary.printFailuresTo(out);

        long failed = summary.getTestsFailedCount() + summary.getContainersFailedCount();
        System.out.println("[TestMain] tests succeeded=" + summary.getTestsSucceededCount()
                + " failed=" + summary.getTestsFailedCount()
                + " skipped=" + summary.getTestsSkippedCount());
        if (summary.getTestsStartedCount() == 0) {
            System.out.println("[TestMain] FAIL: no tests discovered");
            System.exit(1);
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private TestMain() {
    }
}
