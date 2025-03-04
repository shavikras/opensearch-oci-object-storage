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

import static org.opensearch.common.settings.Setting.Property;
import static org.opensearch.common.settings.Setting.boolSetting;
import static org.opensearch.common.settings.Setting.byteSizeSetting;
import static org.opensearch.common.settings.Setting.simpleString;

import java.util.function.Function;
import lombok.extern.log4j.Log4j2;
import org.opensearch.cluster.metadata.RepositoryMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.repositories.RepositoryException;
import org.opensearch.repositories.blobstore.BlobStoreRepository;

/** Blob repository that corresponds to OCI */
@Log4j2
public class OciObjectStorageRepository extends BlobStoreRepository {

    /**
     * Big files can be broken down into chunks during snapshotting if needed. Default value to 1g.
     * MIN value to 5 mb. MAX value to 10 tb.
     */
    static final ByteSizeValue DEFAULT_CHUNK_SIZE = new ByteSizeValue(1, ByteSizeUnit.GB);

    static final ByteSizeValue MIN_CHUNK_SIZE = new ByteSizeValue(5, ByteSizeUnit.MB);
    static final ByteSizeValue MAX_CHUNK_SIZE = new ByteSizeValue(10, ByteSizeUnit.TB);

    public static final String TYPE = "oci";

    public static final Setting<String> BUCKET_SETTING =
            simpleString("bucket", Property.NodeScope, Property.Dynamic);
    public static final Setting<String> NAMESPACE_SETTING =
            simpleString("namespace", Property.NodeScope, Property.Dynamic);
    // Force repository bucket creation if not exists
    public static final Setting<Boolean> FORCE_BUCKET_CREATION_SETTING =
            boolSetting(
                    "forceBucketCreation",
                    false,
                    Setting.Property.NodeScope,
                    Setting.Property.Dynamic);
    // if force creating buckets need to provide the compartment ID under which they will be created
    public static final Setting<String> BUCKET_COMPARTMENT_ID_SETTING =
            simpleString("bucket_compartment_id", Property.NodeScope, Property.Dynamic);
    public static final Setting<String> BASE_PATH_SETTING =
            simpleString("base_path", Property.NodeScope, Property.Dynamic);
    public static final Setting<ByteSizeValue> CHUNK_SIZE_SETTING =
            byteSizeSetting(
                    "chunk_size",
                    DEFAULT_CHUNK_SIZE,
                    MIN_CHUNK_SIZE,
                    MAX_CHUNK_SIZE,
                    Property.NodeScope,
                    Property.Dynamic);
    public static final Setting<String> CLIENT_NAME_SETTINGS =
            new Setting<>("client", "default", Function.identity());

    /** Path to a credentials file */
    public static final Setting<String> CREDENTIALS_FILE_SETTING =
            simpleString("credentials_file", Setting.Property.NodeScope, Setting.Property.Dynamic);

    /** An override for the Object Storage endpoint to connect to. */
    public static final Setting<String> ENDPOINT_SETTING =
            simpleString("endpoint", Setting.Property.NodeScope, Setting.Property.Dynamic);

    /** An override for the region. */
    public static final Setting<String> REGION_SETTING =
            simpleString("region", Setting.Property.NodeScope, Setting.Property.Dynamic);

    public static final Setting<String> USER_ID_SETTING =
            simpleString("userId", Setting.Property.NodeScope, Setting.Property.Dynamic);
    public static final Setting<String> TENANT_ID_SETTING =
            simpleString("tenantId", Setting.Property.NodeScope, Setting.Property.Dynamic);
    public static final Setting<String> FINGERPRINT_SETTING =
            simpleString("fingerprint", Setting.Property.NodeScope, Setting.Property.Dynamic);
    public static final Setting<Boolean> INSTANCE_PRINCIPAL =
            boolSetting(
                    "useInstancePrincipal",
                    false,
                    Setting.Property.NodeScope,
                    Setting.Property.Dynamic);
    private final OciObjectStorageService storageService;
    private BlobPath basePath;
    private ByteSizeValue chunkSize;

    OciObjectStorageRepository(
            final RepositoryMetadata metadata,
            final NamedXContentRegistry namedXContentRegistry,
            final OciObjectStorageService storageService,
            final ClusterService clusterService,
            final RecoverySettings recoverySettings) {
        super(metadata, namedXContentRegistry, clusterService, recoverySettings);
        loadOCIRepoMetadata();
        validateOCIRepoMetadata(metadata);
        this.storageService = storageService;
        // Refreshes the client settings
        storageService.refreshAndClearCache(
                metadata.name(), new OciObjectStorageClientSettings(metadata));
    }

    private void loadOCIRepoMetadata() {
        Settings settings = metadata.settings();
        String basePath = BASE_PATH_SETTING.get(settings);
        if (Strings.hasLength(basePath)) {
            BlobPath path = new BlobPath();
            for (String elem : basePath.split("/")) {
                path = path.add(elem);
            }
            this.basePath = path;
        } else {
            this.basePath = BlobPath.cleanPath();
        }

        this.chunkSize = CHUNK_SIZE_SETTING.get(settings);
    }

    @Override
    protected OciObjectStorageBlobStore createBlobStore() {
        return new OciObjectStorageBlobStore(storageService, metadata);
    }

    @Override
    public BlobPath basePath() {
        return basePath;
    }

    @Override
    protected ByteSizeValue chunkSize() {
        return chunkSize;
    }

    @Override
    public void validateMetadata(RepositoryMetadata repositoryMetadata) {
        super.validateMetadata(repositoryMetadata);
        validateOCIRepoMetadata(repositoryMetadata);
    }

    private void validateOCIRepoMetadata(RepositoryMetadata repositoryMetadata) {
        validateSetting(CLIENT_NAME_SETTINGS, repositoryMetadata);
        validateSetting(ENDPOINT_SETTING, repositoryMetadata);
        validateSetting(BUCKET_SETTING, repositoryMetadata);
        validateSetting(NAMESPACE_SETTING, repositoryMetadata);
        validateSetting(BUCKET_COMPARTMENT_ID_SETTING, repositoryMetadata);
        // validateSetting(BASE_PATH_SETTING, repositoryMetadata);
        boolean isInstancePrincipal = INSTANCE_PRINCIPAL.get(repositoryMetadata.settings());
        //        if (!isInstancePrincipal) {
        //            validateSetting(USER_ID_SETTING, repositoryMetadata);
        //            validateSetting(TENANT_ID_SETTING, repositoryMetadata);
        //            validateSetting(FINGERPRINT_SETTING, repositoryMetadata);
        //            validateSetting(CREDENTIALS_FILE_SETTING, repositoryMetadata);
        //            validateSetting(REGION_SETTING, repositoryMetadata);
        //        }
    }

    /**
     * Get a given setting from the repository settings, throwing a {@link RepositoryException} if
     * the setting does not exist or is empty.
     */
    private <T> void validateSetting(Setting<T> setting, RepositoryMetadata metadata) {
        T value = setting.get(metadata.settings());
        if (value == null) {
            throw new RepositoryException(
                    metadata.name(),
                    "Setting [" + setting.getKey() + "] is not defined for repository");
        }
        if (value instanceof String && !Strings.hasText((String) value)) {
            throw new RepositoryException(
                    metadata.name(), "Setting [" + setting.getKey() + "] is empty for repository");
        }
    }

    @Override
    public boolean isReloadable() {
        return true;
    }

    @Override
    public void reload(RepositoryMetadata repositoryMetadata) {
        if (isReloadable()) {
            super.reload(repositoryMetadata);
            loadOCIRepoMetadata();
            // Refreshes the client settings
            storageService.refreshAndClearCache(
                    repositoryMetadata.name(),
                    new OciObjectStorageClientSettings(repositoryMetadata));
        }
    }
}
