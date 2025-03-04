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
import java.security.AllPermission;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.RepositoryPlugin;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.Region;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.http.client.HttpProvider;

/** The plugin class */
@Log4j2
@SuppressWarnings("deprecation")
public class OciObjectStoragePlugin extends Plugin implements RepositoryPlugin {

    // package-private for tests
    final OciObjectStorageService storageService;
    final Settings settings;

    private static final String DYNAMIC_CORE_REGIONS_IMPORT_PATH =
            "/etc/rbcp_core_regions_artifacts/rbcp_core_regions_metadata.json";

    @SuppressWarnings("deprecation")
    public OciObjectStoragePlugin(final Settings settings) {
        this.storageService = createStorageService();
        this.settings = settings;

        /*
         * Work around security bug while using Instance Principal Authentication
         * where adding the required java.net.SocketPermission to oci-repository-plugin/src/main/resources/plugin-security.policy
         * doesn't fix the issue. See https://github.com/opensearch-project/opensearch-oci-object-storage/issues/13
         */
        AccessController.doPrivileged(
                (PrivilegedAction<Object>)
                        () -> {
                            System.setSecurityManager(
                                    new SecurityManager() {

                                        @Override
                                        public void checkPermission(Permission perm) {
                                            if (perm instanceof AllPermission) {
                                                throw new SecurityException();
                                            }
                                        }
                                    });
                            return null;
                        });
        // loadDynamicCoreRegions();
        // Hack to force Jersey to load first as a default provider
        HttpProvider.getDefault();
    }

    @SuppressWarnings("deprecation")
    private void loadDynamicCoreRegions() {
        AccessController.doPrivileged(
                (PrivilegedAction<Object>)
                        () -> {
                            try {
                                // Region.enableInstanceMetadataService();
                                Region.registerFromInstanceMetadataService();
                                //
                                // CoreRegionsRegistrarHelper.importDataFromJsonPath(
                                //
                                // Paths.get(DYNAMIC_CORE_REGIONS_IMPORT_PATH));
                                /* Validating dynamic core-regions settings import:
                                  The region 'me-dcc-doha-1' is available via the dynamic core-regions metadata file,
                                  and this region is the recommended one to validate that the metadata import succeeded.
                                  Please see more info here: https://confluence.oci.oraclecorp.com/x/J0ett
                                */
                                // Region.fromRegionId("me-dcc-doha-1");
                                log.info(
                                        "Successfully imported dynamic core-regions settings from"
                                                + " JSON configuration file.");
                            } catch (Exception e) {
                                log.error(
                                        "Failed to import dynamic core-regions settings from JSON"
                                                + " configuration file: "
                                                + e.getMessage());
                                throw e;
                            }
                            return null;
                        });
    }

    // overridable for tests
    protected OciObjectStorageService createStorageService() {
        return new OciObjectStorageService();
    }

    @Override
    public Map<String, Repository.Factory> getRepositories(
            Environment env,
            NamedXContentRegistry namedXContentRegistry,
            ClusterService clusterService,
            RecoverySettings recoverySettings) {
        return Collections.singletonMap(
                OciObjectStorageRepository.TYPE,
                metadata ->
                        new OciObjectStorageRepository(
                                metadata,
                                namedXContentRegistry,
                                this.storageService,
                                clusterService,
                                recoverySettings));
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(
                OciObjectStorageRepository.CREDENTIALS_FILE_SETTING,
                OciObjectStorageRepository.ENDPOINT_SETTING,
                OciObjectStorageRepository.REGION_SETTING,
                OciObjectStorageRepository.FINGERPRINT_SETTING,
                OciObjectStorageRepository.INSTANCE_PRINCIPAL,
                OciObjectStorageRepository.TENANT_ID_SETTING,
                OciObjectStorageRepository.USER_ID_SETTING,
                OciObjectStorageRepository.BASE_PATH_SETTING,
                OciObjectStorageRepository.BUCKET_SETTING,
                OciObjectStorageRepository.BUCKET_COMPARTMENT_ID_SETTING,
                OciObjectStorageRepository.FORCE_BUCKET_CREATION_SETTING,
                OciObjectStorageRepository.NAMESPACE_SETTING);
    }

    @Override
    public void close() throws IOException {
        super.close();
        storageService.close();
    }
}
