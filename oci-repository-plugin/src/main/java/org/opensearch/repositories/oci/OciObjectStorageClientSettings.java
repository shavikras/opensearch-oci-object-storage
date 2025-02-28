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

import static org.opensearch.repositories.oci.OciObjectStorageRepository.*;

import java.util.Objects;
import lombok.extern.log4j.Log4j2;
import org.opensearch.cluster.metadata.RepositoryMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.Region;

/** Container for OCI object storage clients settings. */
@Log4j2
public class OciObjectStorageClientSettings {
    public static final String DEV_REGION = "us-ashburn-1";

    /** Path to a credentials file */

    /** An override for the Object Storage endpoint to connect to. */

    /** An override for the region. */
    private final String clientName;

    /** The credentials used by the client to connect to the Storage endpoint. */

    /** The Storage endpoint URL the client should talk to. Null value sets the default. */
    private final String endpoint;

    private final boolean isInstancePrincipal;
    private String userId;
    private String tenantId;
    private String fingerprint;
    private String credentialsFilePath;

    /** The region to access storage service */
    private Region region;

    OciObjectStorageClientSettings(final RepositoryMetadata metadata) {
        Settings settings = metadata.settings();
        this.clientName = CLIENT_NAME_SETTINGS.get(settings);
        this.endpoint = ENDPOINT_SETTING.get(settings);
        this.isInstancePrincipal = INSTANCE_PRINCIPAL.get(settings);

        // If we are not using instance principal we are going to have to provide user principal
        // info
        if (!isInstancePrincipal) {
            // If we are not using instance principal we are going to have to provide user principal
            this.userId = USER_ID_SETTING.get(settings);
            this.tenantId = TENANT_ID_SETTING.get(settings);
            this.fingerprint = FINGERPRINT_SETTING.get(settings);
            this.credentialsFilePath = CREDENTIALS_FILE_SETTING.get(settings);
            this.region = Region.fromRegionCodeOrId(REGION_SETTING.get(settings));
        }
        // We are using instance principal and therefore we are going to use instance principal
        // provider
    }

    public String getClientName() {
        return clientName;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean isInstancePrincipal() {
        return isInstancePrincipal;
    }

    public String getUserId() {
        return userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getCredentialsFilePath() {
        return credentialsFilePath;
    }

    public Region getRegion() {
        return region;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OciObjectStorageClientSettings)) return false;
        OciObjectStorageClientSettings that = (OciObjectStorageClientSettings) o;
        return isInstancePrincipal == that.isInstancePrincipal
                && clientName.equals(that.clientName)
                && endpoint.equals(that.endpoint)
                && Objects.equals(userId, that.userId)
                && Objects.equals(tenantId, that.tenantId)
                && Objects.equals(fingerprint, that.fingerprint)
                && Objects.equals(credentialsFilePath, that.credentialsFilePath)
                && Objects.equals(region, that.region);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                clientName,
                endpoint,
                isInstancePrincipal,
                userId,
                tenantId,
                fingerprint,
                credentialsFilePath,
                region);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("client_settings");
        sb.append("{");
        sb.append("endpoint='" + endpoint + '\'');
        sb.append(", isInstancePrincipal=" + isInstancePrincipal);
        if (!isInstancePrincipal) {
            sb.append(", userId='" + userId + '\'');
            sb.append(", tenantId='" + tenantId + '\'');
            sb.append(", region=" + region);
        }
        sb.append("}");
        return sb.toString();
    }
}
