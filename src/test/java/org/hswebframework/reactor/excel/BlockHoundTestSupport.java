package org.hswebframework.reactor.excel;

import reactor.blockhound.BlockHound;

/**
 * Installs the process-wide BlockHound agent once for tests in different packages.
 */
public final class BlockHoundTestSupport {

    private static boolean installed;

    private BlockHoundTestSupport() {
    }

    public static synchronized void install() {
        if (!installed) {
            BlockHound.install();
            installed = true;
        }
    }
}
