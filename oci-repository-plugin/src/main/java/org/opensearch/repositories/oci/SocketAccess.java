/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.repositories.oci;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import org.opensearch.SpecialPermission;
import org.opensearch.common.CheckedRunnable;

/**
 * This plugin uses oci api/client libraries to connect to oci cloud services. For these remote
 * calls the plugin needs {@link java.net.SocketPermission} 'connect' to establish connections. This
 * class wraps the operations requiring access in {@link
 * AccessController#doPrivileged(PrivilegedExceptionAction)} blocks.
 */
public class SocketAccess {

    private SocketAccess() {}

    @SuppressWarnings("deprecation")
    public static <T> T doPrivilegedIOException(PrivilegedExceptionAction<T> operation)
            throws IOException {
        SpecialPermission.check();
        try {
            return AccessController.doPrivileged(operation);
        } catch (PrivilegedActionException privilegedActionException) {
            if (privilegedActionException.getCause() instanceof IOException) {
                throw (IOException) privilegedActionException.getCause();
            } else {
                throw new IOException(privilegedActionException.getCause());
            }
        }
    }

    @SuppressWarnings("deprecation")
    public static void doPrivilegedVoidIOException(CheckedRunnable<IOException> action)
            throws IOException {
        SpecialPermission.check();
        try {
            AccessController.doPrivileged(
                    (PrivilegedExceptionAction<Void>)
                            () -> {
                                action.run();
                                return null;
                            });
        } catch (PrivilegedActionException privilegedActionException) {
            if (privilegedActionException.getCause() instanceof IOException) {
                throw (IOException) privilegedActionException.getCause();
            } else {
                throw new IOException(privilegedActionException.getCause());
            }
        }
    }
}
