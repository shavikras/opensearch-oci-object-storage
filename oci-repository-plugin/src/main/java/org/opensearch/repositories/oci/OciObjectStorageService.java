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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.log4j.Log4j2;
import org.opensearch.common.collect.MapBuilder;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.util.LazyInitializable;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.ClientConfiguration;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.Region;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.objectstorage.ObjectStorageClient;

/** Service class to hold client instances */
@Log4j2
public class OciObjectStorageService implements Closeable {
    private static final int READ_TIMEOUT_MILLIS = 120000;

    /**
     * Dictionary of client instances. Client instances are built lazily from the latest settings.
     */
    private final AtomicReference<
                    Map<
                            OciObjectStorageClientSettings,
                            LazyInitializable<ObjectStorageClientReference, IOException>>>
            clientsCache = new AtomicReference<>(new ConcurrentHashMap<>());

    private final AtomicReference<Map<String, OciObjectStorageClientSettings>> clientSettingsCache =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * Refreshes the client settings and clear the cache. Existing clients are released. Subsequent
     * calls to {@code OciObjectStorageService#client} will return new clients constructed using the
     * new client settings.
     *
     * @param repositoryName name of the repository that would use the client
     * @param clientSettings the new settings to be used for building clients in subsequent requests
     */
    public synchronized void refreshAndClearCache(
            String repositoryName, OciObjectStorageClientSettings clientSettings) {
        // Release a client if exists
        releaseClient(repositoryName);
        // Replace or add new client settings in cache
        clientSettingsCache.set(
                MapBuilder.newMapBuilder(clientSettingsCache.get())
                        .put(repositoryName, clientSettings)
                        .immutableMap());
        log.debug(
                "Client settings are refreshed for repository {}, {}",
                repositoryName,
                clientSettings);
    }

    /**
     * Attempts to retrieve a client from the cache. If the client does not exist it will be created
     * from the latest settings and will populate the cache. The returned instance should not be
     * cached by the calling code. Instead, for each use, the (possibly updated) instance should be
     * requested by calling this method.
     *
     * @return a cached client storage instance that can be used to manage objects (blobs)
     */
    public ObjectStorageClientReference client(String repositoryName) throws IOException {

        final OciObjectStorageClientSettings clientSettings =
                clientSettingsCache.get().get(repositoryName);
        if (clientSettings == null)
            throw new IllegalStateException(
                    "Unknown client settings for repository: " + repositoryName);
        {
            final LazyInitializable<ObjectStorageClientReference, IOException> lazyReference =
                    clientsCache.get().get(clientSettings);
            final ObjectStorageClientReference clientReference =
                    lazyReference != null ? lazyReference.getOrCompute() : null;
            if (clientReference != null && clientReference.refCount() > 0) {
                return clientReference;
            }
        }
        synchronized (this) {
            final LazyInitializable<ObjectStorageClientReference, IOException> existingLazyRef =
                    clientsCache.get().get(clientSettings);
            final ObjectStorageClientReference existingClientRef =
                    existingLazyRef != null ? existingLazyRef.getOrCompute() : null;
            if (existingClientRef != null && existingClientRef.refCount() > 0) {
                return existingClientRef;
            }
            log.debug("Creating and caching a new OCI object storage client: {}", clientSettings);
            LazyInitializable<ObjectStorageClientReference, IOException> lazyClientReference =
                    new LazyInitializable<ObjectStorageClientReference, IOException>(
                            () ->
                                    new ObjectStorageClientReference(
                                            clientSettings, createClient(clientSettings)),
                            clientRef -> clientRef.tryIncRef(),
                            clientRef -> clientRef.decRef());
            clientsCache.set(
                    MapBuilder.newMapBuilder(clientsCache.get())
                            .put(clientSettings, lazyClientReference)
                            .immutableMap());
            return lazyClientReference.getOrCompute();
        }
    }

    /**
     * Creates a client that can be used to manage OCI Object Storage objects. The client is
     * thread-safe.
     *
     * @param clientSettings name of client settings to use, including secure settings
     * @return a new client storage instance that can be used to manage objects (blobs)
     */
    private ObjectStorageClient createClient(OciObjectStorageClientSettings clientSettings)
            throws IOException {

        BasicAuthenticationDetailsProvider authenticationDetailsProvider;
        if (clientSettings.isInstancePrincipal()) {
            authenticationDetailsProvider = toAuthDetailsProvider();
            log.debug("Using instance principal authentication: {}", clientSettings);
        } else {
            authenticationDetailsProvider =
                    toAuthDetailsProvider(
                            () -> {
                                try {
                                    return Files.newInputStream(
                                            PathUtils.get(clientSettings.getCredentialsFilePath()));
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            clientSettings.getRegion(),
                            clientSettings.getUserId(),
                            clientSettings.getTenantId(),
                            clientSettings.getFingerprint());
            log.debug("Using user credentials for authentication: {}", clientSettings);
        }

        final ObjectStorageClient objectStorageClient =
                SocketAccess.doPrivilegedIOException(
                        () ->
                                ObjectStorageClient.builder()
                                        .configuration(
                                                ClientConfiguration.builder()
                                                        .readTimeoutMillis(READ_TIMEOUT_MILLIS)
                                                        .build())
                                        .build(authenticationDetailsProvider));

        objectStorageClient.setEndpoint(clientSettings.getEndpoint());

        return objectStorageClient;
    }

    private BasicAuthenticationDetailsProvider toAuthDetailsProvider(
            Supplier<InputStream> privateKeySupplier,
            Region region,
            String userId,
            String tenantId,
            String fingerprint) {
        /*
         * The SDK's "region code" is the internal enum's public region name.
         */

        // build the new lazy clients
        return SimpleAuthenticationDetailsProvider.builder()
                .userId(userId)
                .tenantId(tenantId)
                // we will release the previous client for this entry if existed
                .region(region)
                .fingerprint(fingerprint)
                .privateKeySupplier(privateKeySupplier)
                .build();

        //        try {
        //            return new SessionTokenAuthenticationDetailsProvider("~/.oci/config",
        // "oc1.ssh");
        //        } catch (IOException e) {
        //            throw new RuntimeException(e);
        //        }
    }

    private BasicAuthenticationDetailsProvider toAuthDetailsProvider() {
        try {
            return InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        } catch (Exception ex) {
            log.error("Failure calling toAuthDetailsProvider", ex);
            throw ex;
        }
    }

    public synchronized void releaseClient(String repositoryName) {
        final OciObjectStorageClientSettings clientSettings =
                clientSettingsCache.get().get(repositoryName);
        LazyInitializable<ObjectStorageClientReference, IOException> lazyClient =
                clientSettings != null ? clientsCache.get().get(clientSettings) : null;
        if (lazyClient != null) {
            try {
                clientsCache.set(
                        MapBuilder.newMapBuilder(clientsCache.get())
                                .remove(clientSettings)
                                .immutableMap());
                lazyClient.reset();
                log.debug(
                        "An OCI object storage client is released and removed from cache: {}",
                        clientSettings);
            } catch (Exception e) {
                log.error("Error when releasing client: " + clientSettings.getClientName(), e);
            }
        }
    }

    @Override
    public void close() {
        for (final LazyInitializable<ObjectStorageClientReference, IOException> lazyClientRef :
                clientsCache.get().values()) {
            // Client will be released when it is not longer used
            lazyClientRef.reset();
        }
        synchronized (this) {
            clientsCache.set(new ConcurrentHashMap<>());
            clientSettingsCache.set(new ConcurrentHashMap<>());
        }
    }
}
